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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
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
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This formula visitor identifies the entity ids that are accessible within conjunction scope from the formula root
 * and detects possible {@link SelectionFormula} in the tree. It prepares:
 *
 * - {@link #getRequirements()} set that to fetch entity with
 * - {@link #getConjunctiveEntities()} entity primary keys to fetch
 * - {@link #getExpectedComputationalCosts()} costs that is estimated to be paid with regular execution
 */
public class PrefetchFormulaVisitor implements FormulaVisitor, FormulaPostProcessor, PrefetchFactory {
	/**
	 * Threshold where we avoid prefetching entities whatsoever.
	 */
	private static final int BITMAP_SIZE_THRESHOLD = 1000;
	/**
	 * Contains set of requirements collected from all {@link SelectionFormula} in the tree.
	 */
	protected final Map<Class<? extends EntityContentRequire>, EntityContentRequire> requirements = new HashMap<>();
	/**
	 * Indexes that were used when visitor was created.
	 */
	@Nonnull
	@Getter private final TargetIndexes<?> targetIndexes;
	/**
	 * Contains all bitmaps of entity ids found in conjunctive scope of the formula.
	 */
	@Nonnull private final List<Bitmap> conjunctiveEntityIds = new LinkedList<>();
	/**
	 * Contains set of entity primary keys (masked by {@link QueryPlanningContext#translateEntityReference(EntityReferenceContract...)}
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

	public PrefetchFormulaVisitor(@Nonnull TargetIndexes<?> targetIndexes) {
		this.targetIndexes = targetIndexes;
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
	 * Method allows to add a requirement that will be used by {@link QueryExecutionContext#prefetchEntities(Bitmap, EntityFetchRequire)}
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

	@Override
	@Nullable
	public Optional<Runnable> createPrefetchLambdaIfNeededOrWorthwhile(@Nonnull QueryExecutionContext queryContext) {
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
			if (getExpectedComputationalCosts() > queryContext.estimatePrefetchCost(conjunctiveEntities.size(), requirements)) {
				if (entitiesToPrefetch == null) {
					entitiesToPrefetch = conjunctiveEntities;
				} else {
					final RoaringBitmap roaringBitmapA = RoaringBitmapBackedBitmap.getRoaringBitmap(entitiesToPrefetch);
					final RoaringBitmap roaringBitmapB = RoaringBitmapBackedBitmap.getRoaringBitmap(conjunctiveEntities);
					entitiesToPrefetch = new BaseBitmap(
						RoaringBitmap.or(roaringBitmapA, roaringBitmapB)
					);
				}
				if (!(targetIndexes.isGlobalIndex() || targetIndexes.isCatalogIndex())) {
					// when narrowed indexes were used we need to filter the prefetched primary keys to the ones that are
					// present in the index
					Assert.isPremiseValid(
						ReducedEntityIndex.class.isAssignableFrom(targetIndexes.getIndexType()),
						"Only reduced entity indexes are supported"
					);
					entitiesToPrefetch = RoaringBitmapBackedBitmap.and(
						new RoaringBitmap[]{
							RoaringBitmapBackedBitmap.getRoaringBitmap(entitiesToPrefetch),
							RoaringBitmap.or(
								targetIndexes.getIndexes().stream()
									.map(index -> ((ReducedEntityIndex) index).getAllPrimaryKeys())
									.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
									.toArray(RoaringBitmap[]::new)
							)
						}
					);
				}
			}
		}

		if (entitiesToPrefetch != null && requirements != null) {
			final Bitmap finalEntitiesToPrefetch = entitiesToPrefetch;
			final EntityFetchRequire finalRequirements = requirements;
			return of(
				() -> queryContext.prefetchEntities(
					finalEntitiesToPrefetch,
					finalRequirements
				)
			);
		} else {
			return empty();
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
	@Nonnull
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
	@Nonnull
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
