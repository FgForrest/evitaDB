/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
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
public class PrefetchFormulaVisitor implements FormulaVisitor, FormulaPostProcessor, PrefetcherFactory {
	/**
	 * Threshold where we avoid prefetching entities whatsoever.
	 */
	private static final int BITMAP_SIZE_THRESHOLD = 1000;
	/**
	 * Contains set of requirements collected from all {@link SelectionFormula} in the tree.
	 */
	protected final DefaultPrefetchRequirementCollector requirements = new DefaultPrefetchRequirementCollector();
	/**
	 * Context of the query planning.
	 */
	private final QueryPlanningContext queryContext;
	/**
	 * Indexes that were used when visitor was created.
	 */
	@Nonnull
	@Getter private final TargetIndexes<?> targetIndexes;
	/**
	 * Contains all bitmaps of entity ids found in conjunctive scope of the formula.
	 */
	@Nonnull private final List<Bitmap> conjunctiveEntityIds = new ArrayList<>(16);
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
	@Nullable
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

	public PrefetchFormulaVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull TargetIndexes<?> targetIndexes
	) {
		this.queryContext = queryContext;
		this.targetIndexes = targetIndexes;
	}

	/**
	 * We don't alter the input formula - just analyze it.
	 */
	@Nonnull
	@Override
	public Formula getPostProcessedFormula() {
		final Formula result = this.outputFormula;
		this.outputFormula = null;
		return Objects.requireNonNull(result, "The visit method was not executed prior to calling `getPostProcessedFormula`!");
	}

	/**
	 * Method allows to add a requirement that will be used for prefetched entities to fetch wide enough scope of
	 * the entity so that all filtering/sorting logic would have all data present for its evaluation.
	 */
	public void addRequirement(@Nonnull EntityContentRequire... requirement) {
		this.requirements.addRequirementsToPrefetch(requirement);
	}

	@Nonnull
	@Override
	public Optional<PrefetchOrder> createPrefetcherIfNeededOrWorthwhile() {
		EntityFetchRequire requirements = null;
		Bitmap entitiesToPrefetch = null;
		// are we forced to prefetch entities from catalog index?
		if (!this.entityReferences.isEmpty()) {
			requirements = getRequirements();
			entitiesToPrefetch = this.entityReferences;
		}
		// do we know entity ids to prefetch?
		if (isPrefetchPossible()) {
			final Bitmap conjunctiveEntities = getConjunctiveEntities();
			requirements = requirements == null ? getRequirements() : requirements;
			// does the prefetch pay off?
			if (
				requirements != null
					&& getExpectedComputationalCosts()
					> this.queryContext.estimatePrefetchCost(conjunctiveEntities.size(), requirements)
			) {
				if (entitiesToPrefetch == null) {
					entitiesToPrefetch = conjunctiveEntities;
				} else {
					final PersistentRoaringBitmap roaringBitmapA = RoaringBitmapBackedBitmap.getRoaringBitmap(entitiesToPrefetch);
					final PersistentRoaringBitmap roaringBitmapB = RoaringBitmapBackedBitmap.getRoaringBitmap(conjunctiveEntities);
					entitiesToPrefetch = new BaseBitmap(
						PersistentRoaringBitmap.or(roaringBitmapA, roaringBitmapB)
					);
				}
				if (!(this.targetIndexes.isGlobalIndex() || this.targetIndexes.isCatalogIndex())) {
					// when narrowed indexes were used we need to filter the prefetched primary keys to the ones that are
					// present in the index
					Assert.isPremiseValid(
						AbstractReducedEntityIndex.class.isAssignableFrom(this.targetIndexes.getIndexType()),
						"Only reduced entity indexes are supported"
					);
					final List<?> indexes = this.targetIndexes.getIndexes();
					final PersistentRoaringBitmap[] indexBitmaps = new PersistentRoaringBitmap[indexes.size()];
					final EntitySchemaContract entitySchema = this.queryContext.getSchema();
					// partitions narrowed to one plan almost always share a single (reference, scope) pair, so the
					// premise below is checked once per distinct pair instead of once per partition
					String lastCheckedReferenceName = null;
					Scope lastCheckedScope = null;
					for (int i = 0; i < indexes.size(); i++) {
						final AbstractReducedEntityIndex reducedIndex = (AbstractReducedEntityIndex) indexes.get(i);
						final String referenceName = reducedIndex.getRepresentativeReferenceKey().referenceName();
						final Scope scope = reducedIndex.getIndexKey().scope();
						if (!referenceName.equals(lastCheckedReferenceName) || scope != lastCheckedScope) {
							// a narrowed index set wins a plan only for a reference indexed for partitioning: a candidate
							// of any other reference carries `NOT_PARTITIONED_INDEX`, and `QueryPlanner` builds no plan -
							// and no prefetch visitor - for an ineligible candidate, debug alternatives included
							Assert.isPremiseValid(
								isIndexedForPartitioning(entitySchema, referenceName, scope),
								() -> "Prefetch narrowed to reduced index `" + reducedIndex.getIndexKey() + "` of a " +
									"reference that is not indexed for partitioning!"
							);
							// every partition of a narrowed set is read through its index object here, which a registry
							// row has none of - so the registry of the reference in the scope must hold no row. A
							// reference indexed for partitioning creates none, but the rows of its former indexing for
							// filtering only survive a raise of its indexing: the premise then fails loudly for every
							// narrowed set of the reference in the scope, whether or not one of its own partitions is a
							// row, instead of leaving a row's owner silently out of the prefetch
							Assert.isPremiseValid(
								hasNoVirtualPartition(referenceName, scope),
								() -> "Prefetch narrowed to reduced index `" + reducedIndex.getIndexKey() + "` of a " +
									"reference whose single-owner partitions are registered as virtual ones!"
							);
							lastCheckedReferenceName = referenceName;
							lastCheckedScope = scope;
						}
						indexBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(reducedIndex.getAllPrimaryKeys());
					}
					entitiesToPrefetch = RoaringBitmapBackedBitmap.and(
						new PersistentRoaringBitmap[]{
							RoaringBitmapBackedBitmap.getRoaringBitmap(entitiesToPrefetch),
							PersistentRoaringBitmap.or(indexBitmaps)
						}
					);
				}
			}
		}

		if (entitiesToPrefetch != null && requirements != null) {
			return of(
				new PrefetchOrder(
					// when there are any conjunctive entities registered, they can be used for filtering purposes
					!this.conjunctiveEntityIds.isEmpty(),
					entitiesToPrefetch,
					requirements
				)
			);
		} else {
			return empty();
		}
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		if (this.outputFormula == null) {
			this.outputFormula = formula;
		}
		if (formula instanceof final SelectionFormula selectionFormula) {
			this.expectedComputationalCosts += selectionFormula.getDelegate().getEstimatedCost();
		}
		if (formula instanceof final RequirementsDefiner requirementsDefiner) {
			final EntityFetchRequire entityRequire = requirementsDefiner.getEntityRequire();
			final EntityContentRequire[] requirements = entityRequire == null ? EntityContentRequire.EMPTY_ARRAY : entityRequire.getRequirements();
			for (EntityContentRequire requirement : requirements) {
				addRequirement(requirement);
			}
		}

		if (this.conjunctiveScope && formula instanceof ConstantFormula constantFormula) {
			final Bitmap bitmap = constantFormula.getDelegate();
			this.conjunctiveEntityIds.add(bitmap);
			final int bitmapSize = bitmap.size();
			this.estimatedBitmapCardinality = this.estimatedBitmapCardinality == -1 || bitmapSize < this.estimatedBitmapCardinality ?
				bitmapSize : this.estimatedBitmapCardinality;
		} else if (formula instanceof final MultipleEntityFormula multipleEntityFormula) {
			this.entityReferences.addAll(multipleEntityFormula.getDirectEntityReferences());
		}

		traverse(formula);
	}

	/**
	 * Returns set of requirements to fetch entities with.
	 */
	@Nullable
	protected EntityFetchRequire getRequirements() {
		return this.requirements.getEntityFetch();
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
		if (this.estimatedBitmapCardinality > BITMAP_SIZE_THRESHOLD || this.conjunctiveEntityIds.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		Bitmap result = this.conjunctiveEntityIds.get(0);
		for (int i = 1; i < this.conjunctiveEntityIds.size(); i++) {
			final PersistentRoaringBitmap roaringBitmapA = RoaringBitmapBackedBitmap.getRoaringBitmap(result);
			final PersistentRoaringBitmap roaringBitmapB = RoaringBitmapBackedBitmap.getRoaringBitmap(
				this.conjunctiveEntityIds.get(i)
			);
			result = new BaseBitmap(PersistentRoaringBitmap.and(roaringBitmapA, roaringBitmapB));
		}
		return result;
	}

	/**
	 * Returns true if there is any entity id known in conjunction scope and at least single {@link SelectionFormula}
	 * is present in the formula tree.
	 */
	private boolean isPrefetchPossible() {
		return !this.conjunctiveEntityIds.isEmpty() && !this.requirements.isEmpty() && this.estimatedBitmapCardinality <= BITMAP_SIZE_THRESHOLD;
	}

	/**
	 * Method returns the expected computational cost in case prefetch would not be executed. This number plays
	 * a key role in evaluating whether to execute optional prefetch or not. Only if our estimated price for
	 * prefetching would be less than result of this method, the prefetch would be executed.
	 */
	private long getExpectedComputationalCosts() {
		return this.expectedComputationalCosts;
	}

	/**
	 * Returns TRUE when the registry of single-owner partitions of the reference in the scope holds nothing. A
	 * reference indexed for partitioning, the only kind a narrowed index set is built for, registers no row, but the
	 * rows it held while indexed for filtering only stay in the registry after its indexing is raised, and then this
	 * is FALSE.
	 *
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return TRUE when no partition of the reference in the scope is registered as a virtual one
	 */
	private boolean hasNoVirtualPartition(@Nonnull String referenceName, @Nonnull Scope scope) {
		return this.queryContext
			.getIndexIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName),
				ReferencedTypeEntityIndex.class
			)
			.map(typeIndex -> typeIndex.getVirtualReferencePartitionIndex().isEmpty())
			.orElse(true);
	}

	/**
	 * Tells whether the reference is indexed for partitioning in the given scope.
	 *
	 * @param entitySchema  the schema of the queried entity
	 * @param referenceName the name of the reference a reduced index of the winning index set belongs to
	 * @param scope         the scope of that reduced index
	 * @return true when the reference is indexed for partitioning in the scope
	 */
	private static boolean isIndexedForPartitioning(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
	}

}
