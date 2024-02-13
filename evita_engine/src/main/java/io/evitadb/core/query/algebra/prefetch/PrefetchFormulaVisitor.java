/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityRequire;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.function.ToLongDoubleIntBiFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * This formula visitor identifies the entity ids that are accessible within conjunction scope from the formula root
 * and detects possible {@link SelectionFormula} in the tree. It prepares:
 *
 * - {@link #getRequirements()} set that to fetch entity with
 * - {@link #getConjunctiveEntities()} entity primary keys to fetch
 * - {@link #getExpectedComputationalCosts()} costs that is estimated to be paid with regular execution
 */
public class PrefetchFormulaVisitor implements FormulaVisitor, FormulaPostProcessor {
	/**
	 * Threshold where we avoid prefetching entities whatsoever.
	 */
	private static final int BITMAP_SIZE_THRESHOLD = 1000;
	/**
	 * Contains set of requirements collected from all {@link SelectionFormula} in the tree.
	 */
	protected final Map<Class<? extends EntityContentRequire>, EntityContentRequire> requirements = new HashMap<>();
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
	 * Flag that signalizes {@link #visit(Formula)} happens in conjunctive scope.
	 */
	protected boolean conjunctiveScope = true;
	/**
	 * Result of {@link FormulaPostProcessor} interface - basically root of the formula. This implementation doesn't
	 * change the input formula tree - just analyzes it.
	 */
	protected Formula outputFormula;
	/**
	 * Contains sum of all collected bitmap cardinalities. If sum is greater than {@link #BITMAP_SIZE_THRESHOLD}
	 * the prefetch will automatically signalize it has no sense.
	 */
	private int estimatedBitmapCardinality = -1;
	/**
	 * Contains aggregated costs that is estimated to be paid with regular execution of the {@link SelectionFormula}.
	 */
	private long expectedComputationalCosts = 0L;
	/**
	 * Default formula for computing the prefetch costs. Can be overridden in tests so that we can make sure multiple
	 * paths of the calculation are tested (i.e. with or without prefetch).
	 */
	private static ToLongDoubleIntBiFunction PREFETCH_COST_ESTIMATOR =
		(prefetchedEntityCount, requirementCount) -> prefetchedEntityCount * requirementCount * 148L;

	/**
	 * Method allows to run given {@link Runnable} with custom prefetch cost estimator.
	 */
	public static <T> T doWithCustomPrefetchCostEstimator(@Nonnull Supplier<T> supplier, @Nonnull ToLongDoubleIntBiFunction costEstimator) {
		final ToLongDoubleIntBiFunction old = PREFETCH_COST_ESTIMATOR;
		try {
			PREFETCH_COST_ESTIMATOR = costEstimator;
			return supplier.get();
		} finally {
			PREFETCH_COST_ESTIMATOR = old;
		}
	}

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
	private static long estimatePrefetchCost(int prefetchedEntityCount, @Nonnull EntityFetchRequire requirements) {
		return PREFETCH_COST_ESTIMATOR.apply(prefetchedEntityCount, requirements.getRequirements().length);
	}

	public PrefetchFormulaVisitor() {
	}

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
	 * Method allows to add a requirement that will be used by {@link QueryContext#prefetchEntities(Bitmap, EntityFetchRequire)}
	 * to fetch wide enough scope of the entity so that all filtering/sorting logic would have all data present
	 * for its evaluation.
	 */
	public void addRequirement(@Nonnull EntityContentRequire... requirement) {
		for (EntityContentRequire theRequirement : requirement) {
			requirements.merge(
				theRequirement.getClass(), theRequirement,
				EntityContentRequire::combineWith
			);
		}
	}

	/**
	 * Method will prefetch the entities identified in {@link PrefetchFormulaVisitor} but only in case the prefetch
	 * is possible and would "pay off". In case the possible prefetching would be more costly than executing the standard
	 * filtering logic, the prefetch is not executed.
	 */
	@Nullable
	public Runnable createPrefetchLambdaIfNeededOrWorthwhile(@Nonnull QueryContext queryContext) {
		EntityFetchRequire requirements = null;
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
			final EntityFetchRequire finalRequirements = requirements;
			return () -> queryContext.prefetchEntities(
				finalEntitiesToPrefetch,
				finalRequirements
			);
		} else {
			return null;
		}
	}

	@Override
	public void visit(@Nonnull Formula formula) {
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
				addRequirement(requirement);
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
	protected EntityFetchRequire getRequirements() {
		return new EntityFetch(
			requirements.values().toArray(new EntityContentRequire[0])
		);
	}

	/**
	 * Traverses the formula into its children. Enable recursive traversal through formula tree.
	 */
	protected void traverse(@Nonnull Formula formula) {
		final boolean formerConjunctiveScope = this.conjunctiveScope;
		try {
			if (!FilterByVisitor.isConjunctiveFormula(formula.getClass())) {
				this.conjunctiveScope = false;
			}
			for (Formula innerFormula : formula.getInnerFormulas()) {
				innerFormula.accept(this);
			}
		} finally {
			this.conjunctiveScope = formerConjunctiveScope;
		}
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

}
