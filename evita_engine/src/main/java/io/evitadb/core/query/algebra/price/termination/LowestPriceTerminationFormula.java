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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.cache.CachedResult;
import io.evitadb.core.cache.CachedPriceRecordsResult;
import io.evitadb.core.cache.CachedResultWeigher;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.PriceRecordLookup;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.BatchArrayIterator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import lombok.Getter;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * LowestPriceTerminationFormula picks lowest filtered price for each entity id as a representative price for it.
 * It may also filter out entity ids which don't pass {@link #sellingPricePredicate} predicate test.
 *
 * This formula consumes and produces {@link Formula} of {@link PriceRecord#entityPrimaryKey() entity ids}. It uses
 * information from underlying formulas that implement {@link FilteredPriceRecordAccessor#getFilteredPriceRecords(QueryExecutionContext)}
 * to access the lowest price of each entity/inner record id combination for filtering purposes.
 *
 * When the filter planner detects that a {@code priceHistogram} extra-result has been requested AND this LP is
 * being built outside any {@code userFilter} scope, it constructs the instance with
 * {@link #collectPerInnerRecordPrices} set to {@code true}, causing {@link #computeInternal()} to also drain
 * every inner-record winning price into the histogram side-output funnel exposed by
 * {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)}. Non-histogram queries leave the flag
 * at {@code false} at construction time and pay zero overhead for the side-output. The flag is mixed into
 * the detached identity so formula identity distinguishes computations with
 * different histogram side-output requirements.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class LowestPriceTerminationFormula extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = -4905806490462655316L;

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Contains query price mode of the current query.
	 */
	@Getter private final QueryPriceMode queryPriceMode;
	/**
	 * Price filter is used to filter out entities which final selling price doesn't match the predicate.
	 */
	@Getter private final PriceRecordPredicate sellingPricePredicate;
	/**
	 * Predicate that filters out individual prices from being calculated in selling price, `null` when every price
	 * takes part. When present, {@link #individualPricePredicateFactory} produced it for this instance alone, and the
	 * key marks it as an opaque input.
	 */
	@Nullable
	private final Predicate<PriceRecordContract> individualPricePredicate;
	/**
	 * Construction state that supplies an independent predicate to each clone, so that a stateful predicate restarts
	 * its cursor; present exactly when {@link #individualPricePredicate} is.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private final Supplier<? extends Predicate<PriceRecordContract>> individualPricePredicateFactory;
	/**
	 * Comparator used for selecting the lowest price for each entity id among all prices for different inner record ids.
	 * Records it considers equal are told apart by
	 * {@link #winsTieOnSellingPrice(PriceRecordContract, PriceRecordContract)}.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final Comparator<PriceRecordContract> priceRecordComparator;
	/**
	 * When `true`, {@link #computeInternal()} additionally collects the winning price record for every
	 * inner-record-id of every entity in the input bitmap into {@link #perInnerRecordPriceRecords}.
	 * The flag is set at construction by `PriceListCompositionTerminationVisitor` when (a) a
	 * `priceHistogram` extra result is requested AND (b) this LP is being built outside any
	 * `userFilter` scope. When `false`, the histogram side-output funnel is never allocated and the
	 * per-inner-record drain branch is never entered, so non-histogram queries pay zero cost.
	 * The flag is mixed into the detached identity to distinguish formulas
	 * with different histogram side-output requirements.
	 */
	private final boolean collectPerInnerRecordPrices;
	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FilteredPriceRecords filteredPriceRecords;
	/**
	 * Per-inner-record winning price records collected during {@link #computeInternal()} when
	 * {@link #collectPerInnerRecordPrices} is `true`. Covers every inner-record-id of every entity in
	 * the input bitmap (irrespective of {@link #sellingPricePredicate}) so the price histogram can
	 * report `LOWEST_PRICE` distributions per inner record without re-running the formula. Stays
	 * `null` on the non-histogram path.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private FilteredPriceRecords perInnerRecordPriceRecords;

	/**
	 * Constructs an LP for the LOWEST_PRICE inner-record-handling path.
	 *
	 * @param collectPerInnerRecordPrices set to `true` by
	 *                                    {@code PriceListCompositionTerminationVisitor} when (a) the query
	 *                                    has a `priceHistogram` extra result and (b) this LP is being built
	 *                                    outside any `userFilter` scope. When `true` the computed formula
	 *                                    additionally populates the per-inner-record histogram side-output
	 *                                    exposed via
	 *                                    {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)}.
	 *                                    Part of the formula identity, so formulas differing only in it never
	 *                                    share a key.
	 */
	public LowestPriceTerminationFormula(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate,
		boolean collectPerInnerRecordPrices
	) {
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = null;
		this.individualPricePredicateFactory = null;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.collectPerInnerRecordPrices = collectPerInnerRecordPrices;
		this.initFields(containerFormula);
	}

	/**
	 * Internal constructor used by every cloning factory ({@link #getCloneWithInnerFormulas},
	 * {@link #withIndividualPricePredicateFactory}). Carries forward the
	 * individual price predicate and the histogram side-output flag — fields that
	 * are absent from the public constructor's API surface but must survive cloning.
	 */
	private LowestPriceTerminationFormula(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate,
		@Nullable Predicate<PriceRecordContract> individualPricePredicate,
		@Nullable Supplier<? extends Predicate<PriceRecordContract>> individualPricePredicateFactory,
		boolean collectPerInnerRecordPrices
	) {
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = individualPricePredicate;
		this.individualPricePredicateFactory = individualPricePredicateFactory;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.collectPerInnerRecordPrices = collectPerInnerRecordPrices;
		this.initFields(containerFormula);
	}

	/**
	 * Creates a termination with an independent individual-price predicate for this computation and each clone.
	 * The factory must restart mutable computation state and preserve filtering behavior on every invocation.
	 *
	 * @param predicateFactory factory restarting any cursor state used to filter individual price records
	 * @return a new termination with an opaque individual-price input
	 */
	@Nonnull
	public LowestPriceTerminationFormula withIndividualPricePredicateFactory(
		@Nonnull Supplier<? extends Predicate<PriceRecordContract>> predicateFactory
	) {
		return new LowestPriceTerminationFormula(
			getDelegate(), this.priceEvaluationContext, this.queryPriceMode, this.sellingPricePredicate,
			Objects.requireNonNull(predicateFactory.get()), predicateFactory, this.collectPerInnerRecordPrices
		);
	}

	/**
	 * Returns `true` when this LP was constructed by `PriceListCompositionTerminationVisitor` with the
	 * histogram side-output flag enabled. Exposed for tests and for {@link FormulaFinder}-based assertions.
	 *
	 * @return `true` when {@link #computeInternal()} will populate the per-inner-record histogram funnel
	 */
	public boolean isCollectingPerInnerRecordPrices() {
		return this.collectPerInnerRecordPrices;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return `true` when this LP was constructed with the histogram side-output flag enabled
	 */
	@Override
	public boolean exposesPerInnerRecordHistogramRecords() {
		return this.collectPerInnerRecordPrices;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		return this.sellingPricePredicate.getRequestedPredicate();
	}

	/**
	 * Returns delegate formula of this container.
	 */
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new LowestPriceTerminationFormula(
			innerFormulas[0],
			this.priceEvaluationContext, this.queryPriceMode, this.sellingPricePredicate,
			this.individualPricePredicateFactory == null ? this.individualPricePredicate :
				Objects.requireNonNull(this.individualPricePredicateFactory.get()),
			this.individualPricePredicateFactory,
			this.collectPerInnerRecordPrices
		);
	}

	@Override
	public long getOperationCost() {
		return 18203;
	}

	/** Freezes and measures all price outputs before publication. */
	@Override
	protected long measurePayload(@Nonnull Bitmap computed) {
		return CachedResultWeigher.measure(
			getKey(), computed, this.filteredPriceRecords, this.perInnerRecordPriceRecords
		);
	}

	/** Captures the bitmap and both price outputs from the same computation. */
	@Nonnull
	@Override
	protected CachedResult toCachedResult(@Nonnull Bitmap computed, int weight) {
		return CachedPriceRecordsResult.of(
			computed, this.filteredPriceRecords, this.perInnerRecordPriceRecords, weight
		);
	}

	/** Restores all side outputs before exposing the memoized bitmap. */
	@Override
	protected void restoreFromCache(@Nonnull CachedResult cached) {
		final CachedPriceRecordsResult prices = (CachedPriceRecordsResult) cached;
		this.filteredPriceRecords = prices.priceRecords();
		this.perInnerRecordPriceRecords = prices.perInnerRecordPriceRecords();
		restoreComputedResult(prices.result());
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		if (this.filteredPriceRecords == null) {
			// init the records first
			compute();
		}
		return this.filteredPriceRecords;
	}

	/**
	 * Returns the per-inner-record winning price records collected during {@link #computeInternal()} for use by the
	 * price histogram. Each entry represents the lowest price of one inner-record-id of one entity, so the histogram
	 * obtains one bucket data point per variant rather than one per entity.
	 *
	 * Triggers {@link #compute()} lazily if it has not been called yet; the result is then memoized in
	 * {@link #perInnerRecordPriceRecords}.
	 *
	 * @throws io.evitadb.exception.GenericEvitaInternalError if this LP was constructed with
	 *   {@link #collectPerInnerRecordPrices} set to {@code false} — i.e. the filter planner did not opt this
	 *   instance into histogram collection. Guards against the histogram computer accidentally calling this on
	 *   an LP whose side-output funnel was never allocated.
	 */
	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecordsForHistogram(@Nonnull QueryExecutionContext context) {
		if (!this.collectPerInnerRecordPrices) {
			throw new GenericEvitaInternalError(
				"Per-inner-record histogram records were not requested for this formula instance — " +
					"the filter planner did not opt this LP into histogram collection."
			);
		}
		if (this.perInnerRecordPriceRecords == null) {
			compute();
		}
		return Objects.requireNonNull(
			this.perInnerRecordPriceRecords,
			"Per-inner-record histogram records were not populated by compute()."
		);
	}

	@Override
	public String toString() {
		return this.sellingPricePredicate.toString();
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// retrieve filtered entity ids from the delegate formula
		final PersistentRoaringBitmap computedRoaringBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(getDelegate().compute());

		// if there are any entities found
		if (!computedRoaringBitmap.isEmpty()) {
			// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
			final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
				getDelegate(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
			);
			// collect price iterators ordered by price list importance
			final PriceRecordLookup[] priceRecordIterators = new PriceRecordLookup[filteredPriceRecordAccessors.size()];
			int idx = 0;
			for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
				priceRecordIterators[idx++] = accessor.getFilteredPriceRecords(this.executionContext)
					.getPriceRecordsLookup();
			}
			// create array for the lowest prices by entity
			final CompositeObjectArray<PriceRecordContract> priceRecordsFunnel = new CompositeObjectArray<>(PriceRecordContract.class, false);
			// PERFORMANCE-BUDGET CRITICAL: per-inner-record histogram funnel is allocated ONCE
			// up-front (hoisted out of the entity loop) and only when the planner asked for it. On the
			// non-histogram path this stays null and the per-entity hot loop sees a single hoisted null-check
			// — do NOT move this allocation inside the loop or replace with a per-entity check.
			final CompositeObjectArray<PriceRecordContract> perInnerRecordFunnel = this.collectPerInnerRecordPrices
				? new CompositeObjectArray<>(PriceRecordContract.class, false)
				: null;
			// create helper associative index for looking up index of the lowest price by entity id in the priceRecordsFunnel
			final IntObjectMap<PriceRecordContract> entityInnerRecordPrice = new IntObjectHashMap<>();
			// create new roaring bitmap builder
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			final int[] buffer = SharedBufferPool.INSTANCE.obtain();
			try {
				final BatchArrayIterator entityIdIterator = new RoaringBitmapBatchArrayIterator(
					computedRoaringBitmap.getBatchIterator(), buffer
				);
				// go through all entity primary keys
				while (entityIdIterator.hasNext()) {
					final int[] batch = entityIdIterator.nextBatch();
					final int lastExpectedEntity = entityIdIterator.getPeek() > 0 ? batch[entityIdIterator.getPeek() - 1] : -1;
					for (int i = 0; i < entityIdIterator.getPeek(); i++) {
						final int entityId = batch[i];
						// clear working inner record identity map
						entityInnerRecordPrice.clear();

						// now iterate over price sets in price list priority
						for (final PriceRecordLookup priceRecords : priceRecordIterators) {
							priceRecords.forEachPriceOfEntity(
								entityId,
								lastExpectedEntity,
								foundPrice -> {
									// record price found for this inner entity id - but only if not already present
									// if it's present it means the price was already found in more prioritized price list
									final int innerRecordId = foundPrice.innerRecordId();
									final PriceRecordContract innerRecordPrice = entityInnerRecordPrice.get(innerRecordId);
									// we need to filter the price using individual price predicate
									// this handles the situation when we want to consider only prices that relate
									// for previously selected selling price (e.g. when we calculate the discount)
									if (innerRecordPrice == null &&
										(this.individualPricePredicate == null || this.individualPricePredicate.test(foundPrice))) {
										entityInnerRecordPrice.put(innerRecordId, foundPrice);
									}
								}
							);
						}

						Assert.isPremiseValid(
							this.individualPricePredicate != null || !entityInnerRecordPrice.isEmpty(),
							"Price for entity with PK " + entityId + " unexpectedly not found!"
						);

						// locate the lowest price of entity id that passes the filter
						boolean anyPriceMatchesTheFilter = false;
						PriceRecordContract lowestPrice = null;
						final ObjectContainer<PriceRecordContract> values = entityInnerRecordPrice.values();
						for (ObjectCursor<PriceRecordContract> value : values) {
							final PriceRecordContract innerRecordPrice = value.value;
							// test whether inner entity price matches the filter
							anyPriceMatchesTheFilter = anyPriceMatchesTheFilter ||
								this.sellingPricePredicate.test(innerRecordPrice);
							if (lowestPrice == null) {
								lowestPrice = innerRecordPrice;
							} else {
								// the amount decides; only an exact tie pays for the price id / inner record id check,
								// which makes the selection independent of the hash map iteration order
								final int byAmount = this.priceRecordComparator.compare(innerRecordPrice, lowestPrice);
								if (byAmount < 0 ||
									(byAmount == 0 && winsTieOnSellingPrice(innerRecordPrice, lowestPrice))) {
									lowestPrice = innerRecordPrice;
								}
							}
						}
						// PERFORMANCE-BUDGET CRITICAL: histogram drain — runs only on the histogram
						// path (perInnerRecordFunnel != null). Collects EVERY inner-record winning price
						// regardless of sellingPricePredicate so the histogram funnel already covers the
						// relaxed-baseline (price-between-cleared) scope and the PriceHistogramComputer can
						// bypass the FilteredPriceRecordsCollector entirely. Uses a fresh iterator on `values`
						// — HPPC reuses one mutable cursor per iterator so no per-element allocation; the cost
						// is paid only when the histogram is requested. The hoisted null-check is what keeps
						// the non-histogram hot loop free of any histogram-related overhead.
						if (perInnerRecordFunnel != null) {
							for (ObjectCursor<PriceRecordContract> value : values) {
								perInnerRecordFunnel.add(value.value);
							}
						}
						if (anyPriceMatchesTheFilter) {
							// if so - entity id continues to output of this formula
							writer.add(entityId);
							// from now on - work with the lowest entity price grouped by inner record
							priceRecordsFunnel.add(lowestPrice);
						}
					}
				}
			} finally {
				SharedBufferPool.INSTANCE.free(buffer);
			}

			// remember the prices selected during computation
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords(
				priceRecordsFunnel.toArray(),
				SortingForm.ENTITY_PK
			);
			// publish histogram side-output only when the planner asked for it
			if (perInnerRecordFunnel != null) {
				this.perInnerRecordPriceRecords = new ResolvedFilteredPriceRecords(
					perInnerRecordFunnel.toArray(),
					SortingForm.NOT_SORTED
				);
			}

			// wrap result into the bitmap
			return new BaseBitmap(writer.get());
		} else {
			// COLD PATH — empty input bitmap. Performance is irrelevant here; the branch exists purely so the
			// hot path above can rely on a non-empty bitmap and skip the corresponding guards.
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords();
			if (this.collectPerInnerRecordPrices) {
				this.perInnerRecordPriceRecords = new ResolvedFilteredPriceRecords();
			}
			return EmptyBitmap.INSTANCE;
		}
	}

	/**
	 * Decides whether `candidate` replaces `incumbent` as the selling price of the entity when both sell for the same
	 * amount in the queried price mode: the lower {@link PriceRecordContract#priceId()} wins, then the lower
	 * {@link PriceRecordContract#innerRecordId()}. Without it the selection among tied records would follow the
	 * iteration order of the per-entity hash map, whose order is seeded per instance - and the discount sorting pairs
	 * the reference price with the inner record of the selected record, so a different pick changes the order of the
	 * result.
	 *
	 * The rule is the one `PricesContract#isBetterByPriceIdThenInner` applies on the entity side to candidates tied on
	 * the amount it compares, and it is all this method promises: the entity side's rule for exact ties. It does not
	 * make the selection agree with the price for sale the entity reports where the two compare different things - the
	 * entity side compares the price with tax in every query price mode, and it represents an inner record by the
	 * price with the lowest price id among those of its most prioritized price list, whereas this formula keeps one
	 * price per inner record - the first one the individual price predicate accepts, taken from the most prioritized
	 * price list that delivers one; where the lookup reads the lowest prices the index keeps, that is the cheapest
	 * without tax, a tie going to the lower price id (see `EntityPrices`).
	 *
	 * @param candidate the record examined now
	 * @param incumbent the record selected so far, selling for the same amount as `candidate`
	 * @return `true` when `candidate` takes the place of `incumbent`
	 */
	private static boolean winsTieOnSellingPrice(
		@Nonnull PriceRecordContract candidate,
		@Nonnull PriceRecordContract incumbent
	) {
		final int byPriceId = Integer.compare(candidate.priceId(), incumbent.priceId());
		return byPriceId < 0 || (byPriceId == 0 && candidate.innerRecordId() < incumbent.innerRecordId());
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		for (final Formula innerFormula : this.innerFormulas) {
			sum += innerFormula.getEstimatedCardinality();
		}
		return sum;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/** Returns detached computation inputs, with formula operands carried by the ordered child keys. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(this.priceEvaluationContext,
			this.queryPriceMode,
			this.sellingPricePredicate.identity(),
			this.individualPricePredicate == null ? null : IdentityToken.OPAQUE,
			this.collectPerInnerRecordPrices);
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param priceEvaluationContext validity and ordered target price indexes
	 * @param queryPriceMode tax mode used to evaluate prices
	 * @param sellingPricePredicate detached selling-price range predicate
	 * @param individualPricePredicate opaque marker for a supplied predicate, absent for unrestricted prices
	 * @param collectPerInnerRecordPrices whether computation also produces per-inner-record histogram prices
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PricePredicate.Identity sellingPricePredicate,
		@Nullable IdentityToken individualPricePredicate,
		boolean collectPerInnerRecordPrices) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.priceEvaluationContext);
			accumulator.add(this.queryPriceMode);
			accumulator.add(this.sellingPricePredicate);
			accumulator.add(this.individualPricePredicate);
			accumulator.add(this.collectPerInnerRecordPrices);
		}
	}

}
