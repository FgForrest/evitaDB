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

import io.evitadb.api.query.require.CombinableEntityContentRequire;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequirements;
import io.evitadb.api.query.require.EntityRequire;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
public class SelectionFormula extends AbstractFormula implements FilteredPriceRecordAccessor, RequirementsDefiner {
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
	 * We've performed benchmark of reading data from disk - using Linux file cache the reading performance was:
	 *
	 * Benchmark                                Mode  Cnt       Score   Error  Units
	 * SenesiThroughputBenchmark.memTableRead  thrpt       140496.829          ops/s
	 *
	 * For two storage parts - this means 280992 reads / sec. When the linux cache would be empty it would require I/O
	 * which may be 40x times slower (source: https://www.quora.com/Is-the-speed-of-SSD-and-RAM-the-same) for 4kB payload
	 * it means that the lowest expectations are 6782 reads / sec.
	 *
	 * Recomputed on 1. mil operations ({@link io.evitadb.spike.FormulaCostMeasurement}) it's cost of 148.
	 */
	private static long estimatePrefetchCost(int prefetchedEntityCount, EntityFetchRequirements requirements) {
		return prefetchedEntityCount * requirements.getRequirements().length * 148L;
	}

	public SelectionFormula(@Nonnull FilterByVisitor filterByVisitor, @Nonnull Formula delegate, @Nonnull EntityToBitmapFilter alternative) {
		super(delegate);
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

	/**
	 * We need to override this method so that sorting logic will communicate with our implementation and doesn't ask
	 * for filtered price records from this formula {@link #getDelegate()} children which would require computation executed
	 * by the {@link #getDelegate()} which we try to avoid by alternative solution.
	 */
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
	public String toString() {
		return "APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE";
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
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

	/**
	 * This formula visitor identifies the entity ids that are accessible within conjunction scope from the formula root
	 * and detects possible {@link SelectionFormula} in the tree. It prepares:
	 *
	 * - {@link #getRequirements()} set that to fetch entity with
	 * - {@link #getConjunctiveEntities()} entity primary keys to fetch
	 * - {@link #getExpectedComputationalCosts()} costs that is estimated to be paid with regular execution
	 */
	public static class PrefetchFormulaVisitor implements FormulaVisitor, FormulaPostProcessor {
		/**
		 * Contains set of formulas that are considered conjunctive for purpose of this visitor.
		 */
		private static final Set<Class<? extends Formula>> CONJUNCTIVE_FORMULAS;
		/**
		 * Threshold where we avoid prefetching entities whatsoever.
		 */
		private static final int BITMAP_SIZE_THRESHOLD = 1000;
		/**
		 * Contains all bitmaps of entity ids found in conjunctive scope of the formula.
		 */
		@Nonnull private final List<Bitmap> conjunctiveEntityIds = new LinkedList<>();
		/**
		 * Contains set of entity primary keys (masked by {@link QueryContext#translateEntityReference(EntityReferenceContract...)}
		 * that needs to be prefetched.
		 */
		@Nonnull private final Bitmap entityReferences = new BaseBitmap();
		/**
		 * Contains sum of all collected bitmap cardinalities. If sum is greater than {@link #BITMAP_SIZE_THRESHOLD}
		 * the prefetch will automatically signalize it has no sense.
		 */
		private int estimatedBitmapCardinality = -1;
		/**
		 * Contains aggregated costs that is estimated to be paid with regular execution of the {@link SelectionFormula}.
		 */
		private long expectedComputationalCosts = 0L;

		static {
			CONJUNCTIVE_FORMULAS = new HashSet<>();
			CONJUNCTIVE_FORMULAS.add(AndFormula.class);
			CONJUNCTIVE_FORMULAS.add(UserFilterFormula.class);
		}

		/**
		 * Contains set of requirements collected from all {@link SelectionFormula} in the tree.
		 */
		protected final Map<Class<? extends CombinableEntityContentRequire>, CombinableEntityContentRequire> requirements = new HashMap<>();
		/**
		 * Flag that signalizes {@link #visit(Formula)} happens in conjunctive scope.
		 */
		protected boolean conjunctiveScope = true;
		/**
		 * Result of {@link FormulaPostProcessor} interface - basically root of the formula. This implementation doesn't
		 * change the input formula tree - just analyzes it.
		 */
		protected Formula outputFormula;

		/**
		 * We don't alter the input formula - just analyze it.
		 */
		@Nonnull
		@Override
		public Formula getPostProcessedFormula() {
			final Formula result = outputFormula;
			this.outputFormula = null;
			return Objects.requireNonNull(result, "The visit method was not executed prior to calling `getPostProcessedFormula`!");
		}

		/**
		 * Method allows to add a requirement that will be used by {@link QueryContext#prefetchEntities(Bitmap, EntityFetchRequirements)}
		 * to fetch wide enough scope of the entity so that all filtering/sorting logic would have all data present
		 * for its evaluation.
		 */
		public void addRequirement(@Nonnull CombinableEntityContentRequire requirement) {
			requirements.merge(
				requirement.getClass(), requirement,
				CombinableEntityContentRequire::combineWith
			);
		}

		/**
		 * Method will prefetch the entities identified in {@link PrefetchFormulaVisitor} but only in case the prefetch
		 * is possible and would "pay off". In case the possible prefetching would be more costly than executing the standard
		 * filtering logic, the prefetch is not executed.
		 */
		@Nullable
		public Runnable createPrefetchLambdaIfNeededOrWorthwhile(@Nonnull QueryContext queryContext) {
			EntityFetchRequirements requirements = null;
			Bitmap entitiesToPrefetch = null;
			// are we forced to prefetch entities from catalog index?
			if (!entityReferences.isEmpty()) {
				requirements = getRequirements();
				entitiesToPrefetch = entityReferences;
			}
			// do we know entity ids to prefetch?
			if (isPrefetchPossible()) {
				final Bitmap conjunctiveEntities = getConjunctiveEntities();
				requirements = requirements == null ? getRequirements() : requirements;
				// does the prefetch pay off?
				if (getExpectedComputationalCosts() > estimatePrefetchCost(conjunctiveEntities.size(), requirements)) {
					if (entitiesToPrefetch == null) {
						entitiesToPrefetch = conjunctiveEntities;
					} else {
						final RoaringBitmap roaringBitmapA = RoaringBitmapBackedBitmap.getRoaringBitmap(entitiesToPrefetch);
						final RoaringBitmap roaringBitmapB = RoaringBitmapBackedBitmap.getRoaringBitmap(conjunctiveEntities);
						entitiesToPrefetch = new BaseBitmap(
							RoaringBitmap.or(roaringBitmapA, roaringBitmapB)
						);
					}
				}
			}

			if (entitiesToPrefetch != null && requirements != null) {
				final Bitmap finalEntitiesToPrefetch = entitiesToPrefetch;
				final EntityFetchRequirements finalRequirements = requirements;
				return () -> queryContext.prefetchEntities(
					finalEntitiesToPrefetch,
					finalRequirements
				);
			} else {
				return null;
			}
		}

		@Override
		public void visit(Formula formula) {
			if (outputFormula == null) {
				this.outputFormula = formula;
			}
			if (formula instanceof final SelectionFormula selectionFormula) {
				expectedComputationalCosts += selectionFormula.getDelegate().getEstimatedCost();
			}
			if (formula instanceof final RequirementsDefiner requirementsDefiner) {
				final EntityRequire entityRequire = requirementsDefiner.getEntityRequire();
				final EntityContentRequire[] requirements = entityRequire == null ? new EntityContentRequire[0] : entityRequire.getRequirements();
				for (EntityContentRequire requirement : requirements) {
					Assert.isPremiseValid(
						requirement instanceof CombinableEntityContentRequire,
						"Non-combinable content requirements are currently not supported."
					);
					addRequirement((CombinableEntityContentRequire) requirement);
				}
			}

			if (this.conjunctiveScope && formula instanceof ConstantFormula constantFormula) {
				final Bitmap bitmap = constantFormula.getDelegate();
				this.conjunctiveEntityIds.add(bitmap);
				final int bitmapSize = bitmap.size();
				estimatedBitmapCardinality = estimatedBitmapCardinality == -1 || bitmapSize < estimatedBitmapCardinality ?
					bitmapSize : estimatedBitmapCardinality;
			} else if (formula instanceof final MultipleEntityFormula multipleEntityFormula) {
				entityReferences.addAll(multipleEntityFormula.getDirectEntityReferences());
			}

			traverse(formula);
		}

		/**
		 * Returns set of requirements to fetch entities with.
		 */
		protected EntityFetchRequirements getRequirements() {
			return new EntityFetch(
				requirements.values().toArray(new CombinableEntityContentRequire[0])
			);
		}

		/**
		 * Returns entity primary keys to fetch.
		 */
		private Bitmap getConjunctiveEntities() {
			return estimatedBitmapCardinality <= BITMAP_SIZE_THRESHOLD ?
				conjunctiveEntityIds.stream()
					.reduce((bitmapA, bitmapB) -> {
						final RoaringBitmap roaringBitmapA = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmapA);
						final RoaringBitmap roaringBitmapB = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmapB);
						return new BaseBitmap(
							RoaringBitmap.and(roaringBitmapA, roaringBitmapB)
						);
					})
					.orElse(EmptyBitmap.INSTANCE) : EmptyBitmap.INSTANCE;
		}

		/**
		 * Returns true if there is any entity id known in conjunction scope and at least single {@link SelectionFormula}
		 * is present in the formula tree.
		 */
		private boolean isPrefetchPossible() {
			return !conjunctiveEntityIds.isEmpty() && !requirements.isEmpty() && estimatedBitmapCardinality <= BITMAP_SIZE_THRESHOLD;
		}

		/**
		 * Method returns the expected computational cost in case prefetch would not be executed. This number plays
		 * a key role in evaluating whether to execute optional prefetch or not. Only if our estimated price for
		 * prefetching would be less than result of this method, the prefetch would be executed.
		 */
		private long getExpectedComputationalCosts() {
			return expectedComputationalCosts;
		}

		/**
		 * Traverses the formula into its children. Enable recursive traversal through formula tree.
		 */
		protected void traverse(@Nonnull Formula formula) {
			final boolean formerConjunctiveScope = this.conjunctiveScope;
			try {
				if (!CONJUNCTIVE_FORMULAS.contains(formula.getClass())) {
					this.conjunctiveScope = false;
				}
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
			} finally {
				this.conjunctiveScope = formerConjunctiveScope;
			}
		}

	}

}
