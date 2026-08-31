/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.mutation;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.and;
import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.buildWriter;
import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.and;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.andNot;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.or;

/**
 * Unified executor that re-evaluates both facet and histogram expressions for all owner entities
 * affected by a cross-entity change.
 *
 * This executor is a **stateless singleton** -- all collection-specific state is received via
 * {@link IndexMutationTarget}. Registered in {@link IndexMutationExecutorRegistry}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
class ReevaluateExpressionExecutor implements IndexMutationExecutor<ReevaluateExpressionMutation> {

	/**
	 * Structured result of affected owner-entity PK resolution, grouping owner PKs
	 * by `(referencedEntityPK, groupPK)` pair.
	 *
	 * @param groups list of resolved reference groups
	 */
	record AffectedEntityResolution(@Nonnull List<AffectedReferenceGroup> groups) {

		/** Shared empty sentinel for fast-path exits when no owner entities are affected. */
		static final AffectedEntityResolution EMPTY = new AffectedEntityResolution(List.of());

		/**
		 * Returns the union of owner PKs across all groups as a single {@link Bitmap}.
		 *
		 * @return merged bitmap of all owner primary keys; never null, may be empty
		 */
		@Nonnull
		Bitmap allOwnerPKs() {
			if (this.groups.isEmpty()) {
				return new BaseBitmap();
			}
			if (this.groups.size() == 1) {
				return this.groups.get(0).ownerPKs();
			}
			final PersistentRoaringBitmap[] bitmaps = new PersistentRoaringBitmap[this.groups.size()];
			for (int i = 0; i < this.groups.size(); i++) {
				bitmaps[i] = getRoaringBitmap(this.groups.get(i).ownerPKs());
			}
			return new BaseBitmap(or(bitmaps));
		}

		/**
		 * Returns a lazily filtered iterable yielding entries only for owner PKs present in `pks`.
		 *
		 * @param pks filter bitmap; only owner PKs present in this bitmap are yielded
		 * @return lazily filtered iterable over matching entries
		 */
		@Nonnull
		Iterable<AffectedReferenceEntry> entriesForOwnerPKs(@Nonnull Bitmap pks) {
			return () -> new FilteredEntryIterator(this.groups, pks);
		}
	}

	/**
	 * Represents a `(referencedEntityPK, groupPK, ownerPKs)` tuple grouping all owner entities
	 * that share the same referenced entity and (optionally) the same reference group.
	 *
	 * @param referencedEntityPK primary key of the referenced entity
	 * @param groupPK            primary key of the reference group, or `null` for ungrouped references
	 * @param ownerPKs           owner entity PKs holding this reference combination
	 */
	record AffectedReferenceGroup(
		int referencedEntityPK,
		@Nullable Integer groupPK,
		@Nonnull Bitmap ownerPKs
	) {
	}

	/**
	 * Individual entry for iteration during add/remove operations — a single `(reference, owner)` slot.
	 *
	 * @param referencedEntityPK primary key of the referenced entity
	 * @param groupPK            primary key of the reference group, or `null` for ungrouped references
	 * @param ownerPK            primary key of the owner entity
	 */
	record AffectedReferenceEntry(int referencedEntityPK, @Nullable Integer groupPK, int ownerPK) {
	}

	/**
	 * Result of evaluating a trigger's condition against affected owner PKs. Splits the affected set into
	 * two disjoint bitmaps: entities for which the condition is true (should be indexed) and entities for
	 * which it is false (should not be indexed).
	 *
	 * @param shouldBeIndexed    owner PKs for which the condition is now true
	 * @param shouldNotBeIndexed owner PKs for which the condition is now false
	 */
	record ConditionalSplit(@Nonnull Bitmap shouldBeIndexed, @Nonnull Bitmap shouldNotBeIndexed) {
	}

	/**
	 * Entry point: resolves affected owner PKs and re-evaluates registered facet/histogram
	 * triggers for the given cross-entity mutation.
	 *
	 * @param mutation the cross-entity re-evaluation signal
	 * @param target   access to the owning entity collection's schema, triggers, and indexes
	 */
	@Override
	public void execute(
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target
	) {
		final AffectedEntityResolution affected = resolveAffected(target, mutation);
		final Bitmap allAffectedOwnerPKs = affected.allOwnerPKs();
		if (!allAffectedOwnerPKs.isEmpty()) {
			final FacetExpressionTrigger facetTrigger = target.getFacetTrigger(
				mutation.referenceName(), mutation.dependencyType(), mutation.scope()
			);
			if (facetTrigger != null) {
				processFacetTrigger(facetTrigger, mutation, target, affected, allAffectedOwnerPKs);
			}

			final Collection<HistogramExpressionTrigger> histogramTriggers = target.getHistogramTriggers(
				mutation.referenceName(), mutation.scope()
			);
			if (!histogramTriggers.isEmpty()) {
				processHistogramTriggers(
					histogramTriggers, mutation, target, affected, allAffectedOwnerPKs
				);
			}
		}
	}

	/**
	 * Read-only pre-pass companion to {@link #execute}. Resolves the affected owners and evaluates every
	 * histogram trigger's condition against the index state **as it stands right now**, writing nothing.
	 *
	 * `LocalMutationExecutorCollector` calls this before a batch's local mutations are applied, so "right now"
	 * is the pre-mutation state, and hands the result back on
	 * {@link ReevaluateExpressionMutation#previouslyIndexedOwnerPKs()} when the batch is finally dispatched. That
	 * is the only way this executor can learn the *old* condition: by the time {@link #execute} runs, the
	 * index-trigger phase deliberately sits after the container implicit-mutation phase (see
	 * `LocalMutationExecutorCollector`), so every readable source already reflects the post-mutation state.
	 *
	 * The same {@link #evaluateCondition} used for the new state computes the old one, so the two sides cannot
	 * disagree for any reason other than the mutation itself.
	 *
	 * **`null` versus empty is load-bearing.** `null` means *"there was nothing to guard"* — the reference
	 * declares no histogram trigger — and lets {@link #processHistogramTriggers} keep its historical
	 * unrestricted behaviour. A non-null map means the pre-pass genuinely ran and carries one entry per trigger,
	 * an **empty bitmap included**: that says "no owner qualified beforehand", which must suppress every removal,
	 * not fall back to removing them all.
	 *
	 * @param mutation the cross-entity re-evaluation signal about to be applied
	 * @param target   access to the owning entity collection's schema, triggers, and indexes
	 * @return owner PKs whose condition currently holds, keyed by histogram name, or `null` when the reference
	 *         has no histogram triggers at all
	 */
	@Nullable
	public static Map<String, Bitmap> evaluateHistogramConditionState(
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers = target.getHistogramTriggers(
			mutation.referenceName(), mutation.scope()
		);
		if (histogramTriggers.isEmpty()) {
			return null;
		}
		final AffectedEntityResolution affected = resolveAffected(target, mutation);
		final Bitmap allAffectedOwnerPKs = affected.allOwnerPKs();
		final Map<String, Bitmap> result = CollectionUtils.createHashMap(histogramTriggers.size());
		if (allAffectedOwnerPKs.isEmpty()) {
			// no owner referenced the mutated entity yet, so no owner can have contributed — record that
			// explicitly rather than returning null, which would re-enable unrestricted removal
			for (final HistogramExpressionTrigger trigger : histogramTriggers) {
				result.put(trigger.getHistogramIndexName(), EmptyBitmap.INSTANCE);
			}
			return result;
		}
		for (final HistogramExpressionTrigger trigger : histogramTriggers) {
			final ConditionalSplit split = evaluateCondition(
				trigger, mutation, target, affected, allAffectedOwnerPKs
			);
			// materialize the result — the mutations this pre-pass runs ahead of are about to modify the very
			// indexes a passthrough filter plan may hand back by reference, and this bitmap has to survive them.
			// The copy constructor clones the compressed representation when the source is roaring-backed (it
			// always is here); going through `getArray()` would spend an int[] of the full cardinality to
			// rebuild the same thing.
			result.put(
				trigger.getHistogramIndexName(),
				new BaseBitmap(split.shouldBeIndexed())
			);
		}
		return result;
	}

	/**
	 * Narrows the owners a histogram removal may touch to those that actually contributed before this batch —
	 * the answer captured by {@link #evaluateHistogramConditionState} and carried on
	 * {@link ReevaluateExpressionMutation#previouslyIndexedOwnerPKs()}.
	 *
	 * This is what makes the remove side of remove-before-add *paired*. The membership guard it supplements
	 * (`histogramContainsOwner`) can only see that *somebody's* contribution for `(value, owner)` exists, so on
	 * its own it will happily consume a sibling reference's cardinality unit when two of an owner's references
	 * share a bucket key — the defect this restriction exists to prevent.
	 *
	 * @param allAffectedOwnerPKs all owners affected by the mutation
	 * @param mutation            the cross-entity re-evaluation signal
	 * @param histogramName       name of the histogram definition being processed
	 * @return the owners whose contribution may be removed
	 */
	@Nonnull
	private static Bitmap restrictToPreviouslyIndexed(
		@Nonnull Bitmap allAffectedOwnerPKs,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull String histogramName
	) {
		final Map<String, Bitmap> previouslyIndexedByHistogram = mutation.previouslyIndexedOwnerPKs();
		if (previouslyIndexedByHistogram == null) {
			// No pre-pass ran for this mutation, so fall back to the historical (unrestricted) behaviour. In
			// production this is unreachable: `LocalMutationExecutorCollector` is the sole caller of
			// `EntityCollection#applyIndexMutations` and always attaches the captured state. It is reached only
			// by tests that construct a mutation directly. A future second dispatch path that skips the pre-pass
			// would silently reintroduce the sibling-cardinality defect here — attach the state there too.
			return allAffectedOwnerPKs;
		}
		final Bitmap previouslyIndexed = previouslyIndexedByHistogram.get(histogramName);
		if (previouslyIndexed == null || previouslyIndexed.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final int[] restricted = and(
			getRoaringBitmap(allAffectedOwnerPKs), getRoaringBitmap(previouslyIndexed)
		).toArray();
		return restricted.length == 0 ? EmptyBitmap.INSTANCE : new BaseBitmap(restricted);
	}

	/**
	 * Re-evaluates the facet expression for affected owners: splits them into `shouldBeFaceted` /
	 * `shouldNotBeFaceted` sets and synchronises global + reduced indexes.
	 *
	 * @param facetTrigger         the registered facet expression trigger
	 * @param mutation             the cross-entity re-evaluation signal
	 * @param target               access to the entity collection's schema, indexes, and filter evaluator
	 * @param affected             resolved affected groups
	 * @param allAffectedOwnerPKs  union bitmap of all affected owner PKs
	 */
	private static void processFacetTrigger(
		@Nonnull FacetExpressionTrigger facetTrigger,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull Bitmap allAffectedOwnerPKs
	) {
		final ConditionalSplit split = evaluateCondition(facetTrigger, mutation, target, affected, allAffectedOwnerPKs);
		final ReferenceSchemaContract refSchema = target
			.getEntitySchema()
			.getReference(mutation.referenceName())
			.orElseThrow();
		final String referenceName = mutation.referenceName();
		final Scope scope = mutation.scope();
		final EntityIndex globalIndex = target.getOrCreateIndex(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		// Reduced indexes only exist when the reference uses FOR_FILTERING_AND_PARTITIONING indexing.
		final boolean targetReduced =
			refSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
		final ReferencedTypeEntityIndex refTypeIndex;
		if (targetReduced) {
			refTypeIndex = asReferencedTypeEntityIndex(
				target.getIndexIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
				),
				() -> "REFERENCED_ENTITY_TYPE/" + referenceName
			);
		} else {
			refTypeIndex = null;
		}

		// Apply the *same* presence-aware decision matrix the local indexing path uses
		// (ReferenceIndexMutator#applyFacetDecisionMatrix). The cross-entity executor never migrates a
		// reference's group — group affiliation only ever changes via local SetReferenceGroupMutation /
		// RemoveReferenceGroupMutation on the owner entity. A cross-entity trigger merely flips the
		// faceted/not-faceted *decision* for the owner's reference in its current (resolved) group.
		// By the time this executor runs, the local path may have already added the facet, removed it, or
		// left it in a now-stale bucket; the decision matrix scans for the facet's actual location and
		// reconciles it deterministically — missing bucket is a no-op, already-present is a no-op, a
		// wrong bucket is a self-healing move. This eliminates the blind add/removeFacet calls that
		// raised `Facet ... not found in index (group: ...)!` and the symmetric orphan-duplicate on add.
		for (AffectedReferenceEntry entry : affected.entriesForOwnerPKs(split.shouldBeIndexed())) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.referencedEntityPK());
			ReferenceIndexMutator.applyFacetDecisionMatrix(
				globalIndex, refSchema, refKey, entry.groupPK(), entry.ownerPK(), true
			);
			applyFacetToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, true);
		}
		for (AffectedReferenceEntry entry : affected.entriesForOwnerPKs(split.shouldNotBeIndexed())) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.referencedEntityPK());
			ReferenceIndexMutator.applyFacetDecisionMatrix(
				globalIndex, refSchema, refKey, entry.groupPK(), entry.ownerPK(), false
			);
			applyFacetToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, false);
		}
	}

	/**
	 * Propagates the facet decision to every reduced index covering the given referenced entity, using the
	 * same presence-aware {@link ReferenceIndexMutator#applyFacetDecisionMatrix} as the global index. No-op
	 * when `refTypeIndex` is `null` (non-partitioned schemas).
	 *
	 * @param target        access to the entity collection's index store
	 * @param refTypeIndex  the `REFERENCED_ENTITY_TYPE` index, or `null` for non-partitioned schemas
	 * @param refSchema     schema of the reference being updated
	 * @param refKey        the `(referenceName, referencedEntityPK)` key
	 * @param entry         the `(referencedEntityPK, groupPK, ownerPK)` triple
	 * @param nowFaceted    `true` when the owner should be faceted in `entry.groupPK()`, `false` otherwise
	 */
	private static void applyFacetToReducedIndexes(
		@Nonnull IndexMutationTarget target,
		@Nullable ReferencedTypeEntityIndex refTypeIndex,
		@Nonnull ReferenceSchemaContract refSchema,
		@Nonnull ReferenceKey refKey,
		@Nonnull AffectedReferenceEntry entry,
		boolean nowFaceted
	) {
		if (refTypeIndex == null) {
			return;
		}
		final int[] reducedStoragePKs = refTypeIndex.getAllReferenceIndexes(entry.referencedEntityPK());
		for (int reducedStoragePK : reducedStoragePKs) {
			final EntityIndex reducedIndex = target.getOrCreateIndexByPrimaryKey(reducedStoragePK);
			Assert.isPremiseValid(
				reducedIndex != null,
				"Expected reduced index with storage PK " + reducedStoragePK +
					" to exist for referenced entity PK " + entry.referencedEntityPK()
			);
			ReferenceIndexMutator.applyFacetDecisionMatrix(
				reducedIndex, refSchema, refKey, entry.groupPK(), entry.ownerPK(), nowFaceted
			);
		}
	}

	/**
	 * Re-evaluates all registered histogram triggers for affected owners using a three-step
	 * remove-stale / remove-before-add / add-current pattern.
	 *
	 * @param histogramTriggers    histogram triggers for the affected reference and scope
	 * @param mutation             the cross-entity re-evaluation signal
	 * @param target               access to the entity collection's schema, indexes, and filter evaluator
	 * @param affected             resolved affected groups
	 * @param allAffectedOwnerPKs  union bitmap of all affected owner PKs
	 */
	private static void processHistogramTriggers(
		@Nonnull Collection<HistogramExpressionTrigger> histogramTriggers,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull Bitmap allAffectedOwnerPKs
	) {
		final String referenceName = mutation.referenceName();
		final Scope scope = mutation.scope();
		final ReferenceSchemaContract refSchema = target.getEntitySchema()
			.getReference(referenceName).orElseThrow();
		// Grouped references store per-group reduced indexes; ungrouped references use REFERENCED_ENTITY_TYPE.
		final boolean isGrouped = refSchema.getReferencedGroupType() != null;
		final EntityIndexType rteiType = isGrouped
			? EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
			: EntityIndexType.REFERENCED_ENTITY_TYPE;
		final ReferencedTypeEntityIndex rtei = findReferencedTypeEntityIndexForModification(
			target, rteiType, scope, referenceName
		);

		for (final HistogramExpressionTrigger trigger : histogramTriggers) {
			final String histogramName = trigger.getHistogramIndexName();
			final ConditionalSplit split = evaluateCondition(trigger, mutation, target, affected, allAffectedOwnerPKs);

			// Determine the set of locales for localized histograms; for non-localized, use a singleton null set.
			final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
			final Set<Locale> locales;
			if (resolution.localized()) {
				final String sourceEntityType = Objects.requireNonNull(resolution.sourceEntityType());
				if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
					locales = target.getEntitySchemaLocales(sourceEntityType);
				} else {
					// reference attribute — locales come from the owner entity schema
					locales = target.getEntitySchemaLocales(target.getEntitySchema().getName());
				}
			} else {
				locales = Set.of();
			}

			// remove stale entries + remove-before-add
			// Scoped removal is safe for all dependency types when the value source is a referenced
			// entity attribute, because AffectedReferenceGroup.referencedEntityPK() is always correctly
			// populated — for GROUP deps from rgei.getReferencedEntityPrimaryKeys(), for PARENT deps
			// from RTEI/RGEI traversal intersected with children bitmap.
			final boolean canUseScopedRemoval = resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE;
			// only owners that actually contributed before this batch may have their contribution removed —
			// removing on mere bucket membership consumes a sibling reference's cardinality unit
			final Bitmap ownerPKsToRemove = restrictToPreviouslyIndexed(
				allAffectedOwnerPKs, mutation, histogramName
			);
			if (canUseScopedRemoval) {
				// Scoped removal: deterministic remove using known values from source FilterIndex
				// or pre-mutation captured values when the value source attribute itself changed.
				scopedRemoveForReferencedEntityAttribute(
					histogramName, resolution, ownerPKsToRemove,
					affected, rtei, isGrouped, target, locales, mutation, scope
				);
				addHistogramEntries(
					histogramName, trigger, split.shouldBeIndexed(), affected, rtei, isGrouped,
					target, referenceName, scope, locales
				);
			} else {
				// REFERENCE_ATTRIBUTE path: deterministic remove+re-add using values from the
				// reference attribute FilterIndex (same index the add path reads)
				removeFromReferenceAttribute(
					histogramName, resolution, ownerPKsToRemove, affected,
					rtei, isGrouped, target, referenceName, locales
				);
				addHistogramEntries(
					histogramName, trigger, split.shouldBeIndexed(), affected, rtei, isGrouped,
					target, referenceName, scope, locales
				);
			}
		}
	}

	/**
	 * Evaluates the trigger's FilterBy constraint against affected owner PKs and splits them into two disjoint
	 * sets: those for which the condition is now true (should be indexed) and those for which it is false
	 * (should not be indexed). For unconditional triggers (no FilterBy), all affected PKs are in the "true" set.
	 *
	 * When the condition filter contains a `groupHaving` clause and the mutation fires for a referenced entity
	 * attribute change (not a group entity change), the evaluation is performed per-group to avoid cross-reference
	 * false positives. Without per-group scoping, a product with references in multiple groups (e.g., PV=3 in
	 * group=2/CHECKBOX and PV=5 in group=1/INTERVAL) could incorrectly match because `groupHaving(INTERVAL)`
	 * matches via group=1 while `entityHaving(PK=3)` matches via a different reference — the `and` doesn't
	 * enforce same-reference semantics.
	 *
	 * @param trigger             the expression trigger carrying the optional FilterBy constraint
	 * @param mutation            the cross-entity re-evaluation signal
	 * @param target              access to the entity collection's filter evaluator
	 * @param affected            resolved affected groups with per-group owner PKs
	 * @param allAffectedOwnerPKs union bitmap of all affected owner PKs
	 * @return split result with disjoint shouldBeIndexed / shouldNotBeIndexed bitmaps
	 */
	@Nonnull
	private static ConditionalSplit evaluateCondition(
		@Nonnull ExpressionIndexTrigger trigger,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull Bitmap allAffectedOwnerPKs
	) {
		if (!trigger.hasFilterByConstraint()) {
			return new ConditionalSplit(allAffectedOwnerPKs, new BaseBitmap());
		}
		final DependencyType depType = mutation.dependencyType();
		final boolean needsPerGroupEvaluation =
			(depType == DependencyType.REFERENCED_ENTITY_ATTRIBUTE
				|| depType == DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE)
				&& affected.groups().stream().anyMatch(g -> g.groupPK() != null)
				&& !FinderVisitor.findConstraints(
					trigger.getFilterByConstraint(),
					GroupHaving.class::isInstance
				).isEmpty();

		if (needsPerGroupEvaluation) {
			return evaluateConditionPerGroup(trigger, mutation, target, affected);
		} else {
			return evaluateConditionGlobal(trigger, mutation, target, allAffectedOwnerPKs);
		}
	}

	/**
	 * Global evaluation: runs a single parameterized filter against all affected owner PKs.
	 * Used when per-group scoping is not needed (no groupHaving in the condition, or the mutation
	 * already scopes by group PK).
	 */
	@Nonnull
	private static ConditionalSplit evaluateConditionGlobal(
		@Nonnull ExpressionIndexTrigger trigger,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull Bitmap allAffectedOwnerPKs
	) {
		final FilterBy parameterizedFilter = parameterize(
			trigger.getFilterByConstraint(), mutation.referenceName(),
			mutation.mutatedEntityPK(), mutation.dependencyType()
		);
		final Bitmap truePKs = target.evaluateFilter(parameterizedFilter, mutation.scope());
		final Bitmap shouldBeIndexed = and(
			new PersistentRoaringBitmap[]{
				getRoaringBitmap(allAffectedOwnerPKs),
				getRoaringBitmap(truePKs)
			}
		);
		final Bitmap shouldNotBeIndexed = new BaseBitmap(
			andNot(
				getRoaringBitmap(allAffectedOwnerPKs),
				getRoaringBitmap(truePKs)
			)
		);
		return new ConditionalSplit(shouldBeIndexed, shouldNotBeIndexed);
	}

	/**
	 * Per-group evaluation: for each affected group, parameterizes the filter with both the referenced
	 * entity PK AND the group entity PK, ensuring that `groupHaving` checks only the specific group
	 * of the reference being evaluated, not other groups of the same owner entity.
	 */
	@Nonnull
	private static ConditionalSplit evaluateConditionPerGroup(
		@Nonnull ExpressionIndexTrigger trigger,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected
	) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> shouldBeWriter = buildWriter();
		final RoaringBitmapWriter<PersistentRoaringBitmap> shouldNotBeWriter = buildWriter();
		for (final AffectedReferenceGroup group : affected.groups()) {
			final FilterBy parameterizedFilter = parameterizeWithGroupScope(
				trigger.getFilterByConstraint(), mutation.referenceName(),
				mutation.mutatedEntityPK(), mutation.dependencyType(),
				group.groupPK()
			);
			final Bitmap truePKs = target.evaluateFilter(parameterizedFilter, mutation.scope());
			final PersistentRoaringBitmap groupOwnerPKs = getRoaringBitmap(group.ownerPKs());
			final PersistentRoaringBitmap matched = and(groupOwnerPKs, getRoaringBitmap(truePKs));
			final PersistentRoaringBitmap notMatched = andNot(groupOwnerPKs, getRoaringBitmap(truePKs));
			shouldBeWriter.addMany(matched.toArray());
			shouldNotBeWriter.addMany(notMatched.toArray());
		}
		return new ConditionalSplit(
			new BaseBitmap(shouldBeWriter.get()),
			new BaseBitmap(shouldNotBeWriter.get())
		);
	}

	/**
	 * Extended parameterization that injects both the referenced entity PK (via `entityHaving`)
	 * and the group entity PK (via `groupHaving(entityPrimaryKeyInSet)`) into the filter.
	 * The group PK is injected into the ORIGINAL filter FIRST (before entity PK scoping) so that
	 * the `GroupHaving` clause is still directly accessible for merging. This ensures that the
	 * resulting filter has a single `GroupHaving(and(condition, entityPrimaryKeyInSet(groupPK)))`,
	 * preventing cross-reference false positives.
	 */
	@Nonnull
	private static FilterBy parameterizeWithGroupScope(
		@Nonnull FilterBy triggerFilterBy,
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType,
		@Nullable Integer groupPK
	) {
		if (groupPK == null) {
			return parameterize(triggerFilterBy, referenceName, mutatedEntityPK, dependencyType);
		}
		// Inject group PK scope into the ORIGINAL filter first (before entity PK injection); the
		// recursive rewrite reaches every matching ReferenceHaving regardless of nesting (Or/And/Not),
		// and injectPkScope merges the PK into every GroupHaving sibling in each match.
		final EntityPrimaryKeyInSet groupPkConstraint = new EntityPrimaryKeyInSet(groupPK);
		final FilterBy groupScoped = rewriteMatchingReferenceHavings(
			triggerFilterBy, referenceName, groupPkConstraint, true
		);
		// then apply the standard entity PK scoping
		return parameterize(groupScoped, referenceName, mutatedEntityPK, dependencyType);
	}

	/**
	 * Scoped removal for {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE} sources. Instead
	 * of scanning all histogram buckets and removing the owner PK from each (which destroys entries
	 * contributed by non-affected references), this method only removes the specific value contributed
	 * by each affected referenced entity, identified via the source collection's FilterIndex.
	 *
	 * When the affected entity's current value is NOT found in the histogram (indicating a value
	 * change rather than a condition change), falls back to blanket removal for those owner PKs.
	 *
	 * @param histogramName    name of the histogram definition
	 * @param resolution       value resolution metadata
	 * @param ownerPKsToRemove owner PKs whose histogram entries should be cleared
	 * @param affected         resolved affected groups
	 * @param rtei             the top-level referenced-type entity index
	 * @param isGrouped        `true` when the reference has a group type
	 * @param target           access to entity collection indexes
	 * @param locales          locales for localized histograms; empty set for non-localized
	 * @param mutation         the cross-entity re-evaluation signal carrying optional pre-mutation values
	 */
	private static void scopedRemoveForReferencedEntityAttribute(
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull Set<Locale> locales,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull Scope scope
	) {
		if (ownerPKsToRemove.isEmpty()) {
			return;
		}
		final String sourceEntityType = resolution.sourceEntityType();
		if (sourceEntityType == null) {
			return;
		}
		// resolve pre-mutation old values if the value source attribute was itself mutated
		final Map<Locale, Serializable> preMutationValues = resolvePreMutationValues(
			resolution, mutation
		);
		if (resolution.localized()) {
			for (final Locale locale : locales) {
				scopedRemoveForRefEntityAttrForLocale(
					histogramName, locale, resolution, ownerPKsToRemove,
					affected, rtei, isGrouped, target, preMutationValues, scope
				);
			}
		} else {
			scopedRemoveForRefEntityAttrForLocale(
				histogramName, null, resolution, ownerPKsToRemove,
				affected, rtei, isGrouped, target, preMutationValues, scope
			);
		}
	}

	/**
	 * Resolves pre-mutation attribute values from the mutation's captured map for the given histogram
	 * trigger's value source attribute. Returns null when the value source attribute was not mutated
	 * (condition-only change).
	 *
	 * @param resolution value resolution metadata identifying the histogram's source attribute
	 * @param mutation   the cross-entity mutation carrying optional pre-mutation values
	 * @return per-locale old values, or null if the value source was not mutated
	 */
	@Nullable
	private static Map<Locale, Serializable> resolvePreMutationValues(
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull ReevaluateExpressionMutation mutation
	) {
		final Map<String, Map<Locale, Serializable>> preMutationSourceValues = mutation.preMutationSourceValues();
		return preMutationSourceValues == null ? null : preMutationSourceValues.get(resolution.sourceAttributeName());
	}

	/**
	 * Per-locale implementation of scoped histogram removal. For each affected reference group,
	 * looks up the referenced entity's current value in the source FilterIndex and removes only
	 * that specific (value, ownerPK) pair from the histogram. Owner PKs whose histogram entry
	 * does not match the source value (indicating a value change) are collected for full re-index
	 * by the caller.
	 *
	 * @param histogramName    name of the histogram definition
	 * @param locale           locale for localized histograms, or `null` for non-localized
	 * @param resolution       value resolution metadata
	 * @param ownerPKsToRemove   owner PKs whose histogram entries should be cleared
	 * @param affected           resolved affected groups
	 * @param rtei               the top-level referenced-type entity index
	 * @param isGrouped          `true` when the reference has a group type
	 * @param target             access to entity collection indexes
	 * @param preMutationValues  per-locale pre-mutation raw values when the value source was mutated, or null
	 */
	private static void scopedRemoveForRefEntityAttrForLocale(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nullable Map<Locale, Serializable> preMutationValues,
		@Nonnull Scope scope
	) {
		final String sourceEntityType = Objects.requireNonNull(resolution.sourceEntityType());

		// when the value source attribute was itself mutated, use captured pre-mutation values
		// for deterministic removal (the source FilterIndex already reflects the NEW values)
		final Serializable[] knownOldValues;
		if (preMutationValues != null) {
			final Serializable rawOldValue = preMutationValues.get(locale);
			knownOldValues = ReferenceIndexMutator.resolveHistogramValues(rawOldValue, resolution);
		} else {
			knownOldValues = null;
		}

		final FilterIndex sourceFilterIndex = target.getSourceFilterIndex(
			sourceEntityType, resolution.sourceAttributeName(), locale, scope
		);
		final PersistentRoaringBitmap removePKs = getRoaringBitmap(ownerPKsToRemove);

		for (final AffectedReferenceGroup group : affected.groups()) {
			final PersistentRoaringBitmap groupPKs = getRoaringBitmap(group.ownerPKs());
			final PersistentRoaringBitmap matched = and(removePKs, groupPKs);

			if (!matched.isEmpty()) {
				// values to remove: known old values (value-change) or current source values (condition-change)
				final List<? extends Serializable> valuesToRemove;
				if (knownOldValues != null) {
					// value source changed: use pre-captured old values for deterministic removal
					valuesToRemove = knownOldValues.length > 0 ? List.of(knownOldValues) : List.of();
				} else {
					// condition-only change: current source values == old values (value is unchanged)
					valuesToRemove = resolveAllRefEntityValues(
						sourceFilterIndex, group.referencedEntityPK(), resolution
					);
				}

				// remove each (value, ownerPK) pair from both RGEI and RTEI; skip when the histogram
				// does not contain the entry (never created — condition was false, or different scope)
				for (final Serializable value : valuesToRemove) {
					for (int ownerPK : matched) {
						removeHistogramValue(
							histogramName, locale, value, ownerPK,
							group, rtei, isGrouped, target, resolution.indexedDecimalPlaces()
						);
					}
				}
			}
		}
	}

	/**
	 * Dispatches on the descriptor's `plainType` to produce the value a histogram should emit
	 * for a single source FilterIndex bucket. Range histograms see raw `Range` bucket values
	 * (sourced from the FilterIndex's `InvertedIndex` shadow); scalar histograms see `Number`
	 * instances. Returns `null` to signal the caller should skip the bucket (a non-`Number`
	 * scalar bucket is the only legitimate skip case). Range-typed mismatches with the
	 * descriptor's `plainType` are treated as index/schema drift and surface as a defensive
	 * throw rather than a silent skip.
	 *
	 * @param bucketValue         raw bucket value pulled from the source FilterIndex
	 * @param rangeSource         `true` when the histogram is range-typed (precomputed from
	 *                            {@link HistogramValueDescriptor#innerNumericType()})
	 * @param plainType           the descriptor's `plainType` — authoritative for value typing
	 * @param sourceAttributeName name of the source attribute, used for error reporting
	 * @return the typed value to emit, or `null` to skip the bucket
	 */
	@Nullable
	private static Serializable resolveEmittedBucketValue(
		@Nonnull Serializable bucketValue,
		boolean rangeSource,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull String sourceAttributeName
	) {
		if (rangeSource) {
			if (!plainType.isInstance(bucketValue)) {
				throw new GenericEvitaInternalError(
					"Source FilterIndex for range histogram attribute `" +
						sourceAttributeName + "` emitted bucket value of type `" +
						bucketValue.getClass().getName() + "` but plainType is `" +
						plainType.getName() + "` — index/schema drift."
				);
			}
			return bucketValue;
		}
		return bucketValue instanceof Number numericValue ? numericValue : null;
	}

	/**
	 * Resolves ALL histogram values for the given referenced entity PK by scanning the source
	 * FilterIndex. For scalar attributes returns a singleton list; for array-typed attributes
	 * the entity may appear in multiple source buckets — all matching values are returned. For
	 * range-typed source attributes the matching buckets carry raw `Range` instances (sourced from
	 * the `InvertedIndex` shadow) and are returned as-is.
	 *
	 * Falls back to a singleton list containing
	 * {@link HistogramValueDescriptor#defaultValue()} if the entity is not in any bucket; the
	 * descriptor enforces `defaultValue == null` for range-typed sources, so the fallback is
	 * scalar-only by construction.
	 *
	 * @param sourceFilterIndex the source collection's FilterIndex for the value attribute
	 * @param refEntityPK       primary key of the referenced entity
	 * @param resolution        value resolution metadata; used to detect range-typed sources and
	 *                          to enforce the descriptor's `plainType` invariant on bucket values
	 * @return list of resolved values typed to {@link HistogramValueDescriptor#plainType()}; empty
	 *         if no value can be determined
	 */
	@Nonnull
	private static List<? extends Serializable> resolveAllRefEntityValues(
		@Nullable FilterIndex sourceFilterIndex,
		int refEntityPK,
		@Nonnull HistogramValueDescriptor resolution
	) {
		final Number defaultValue = resolution.defaultValue();
		if (sourceFilterIndex != null) {
			final boolean rangeSource = resolution.innerNumericType() != null;
			final Class<? extends Serializable> plainType = resolution.plainType();
			final ValueToRecord[] buckets = sourceFilterIndex.getHistogramOfAllRecords().getBuckets();
			List<Serializable> result = null;
			for (final ValueToRecord bucket : buckets) {
				if (bucket.getRecordIds().contains(refEntityPK)) {
					final Serializable emittedValue = resolveEmittedBucketValue(
						bucket.getValue(), rangeSource, plainType, resolution.sourceAttributeName()
					);
					if (emittedValue != null) {
						if (result == null) {
							result = new ArrayList<>(4);
						}
						result.add(emittedValue);
					}
				}
			}
			if (result != null) {
				return result;
			}
		}
		return defaultValue != null ? List.of(defaultValue) : List.of();
	}

	/**
	 * Inserts histogram values for eligible owner PKs, delegating to the appropriate source-specific method
	 * based on {@link HistogramValueDescriptor#source()}.
	 *
	 * @param histogramName            name of the histogram definition
	 * @param trigger                  the histogram trigger with value resolution metadata
	 * @param histogramShouldBeIndexed owner PKs that must have a histogram value
	 * @param affected                 resolved affected groups
	 * @param rtei                     the top-level referenced-type index
	 * @param isGrouped                `true` when the reference has a group type
	 * @param target                   access to entity collection indexes
	 * @param referenceName            name of the reference
	 * @param scope                    scope of the entity collection
	 * @param locales                  locales for localized histograms; empty set for non-localized
	 */
	private static void addHistogramEntries(
		@Nonnull String histogramName,
		@Nonnull HistogramExpressionTrigger trigger,
		@Nonnull Bitmap histogramShouldBeIndexed,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Set<Locale> locales
	) {
		if (!histogramShouldBeIndexed.isEmpty()) {
			final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
			if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
				addFromReferencedEntityAttribute(
					histogramName, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target, locales, scope
				);
			} else {
				addFromReferenceAttribute(
					histogramName, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target, referenceName, locales
				);
			}
		}
	}

	/**
	 * Inserts histogram values sourced from a **referenced entity attribute**. Scans the source
	 * collection's `FilterIndex` and applies default values for missing referenced entities.
	 * For localized attributes, performs the scan-and-insert for each locale separately.
	 *
	 * @param histogramName            name of the histogram definition
	 * @param resolution               value resolution metadata
	 * @param histogramShouldBeIndexed owner PKs that must have a histogram value
	 * @param affected                 resolved affected groups
	 * @param rtei                     the top-level referenced-type index
	 * @param isGrouped                `true` when the reference has a group type
	 * @param target                   access to cross-entity filter indexes
	 * @param locales                  locales for localized histograms; empty set for non-localized
	 */
	private static void addFromReferencedEntityAttribute(
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull Set<Locale> locales,
		@Nonnull Scope scope
	) {
		final String sourceEntityType = resolution.sourceEntityType();
		if (sourceEntityType == null) {
			return;
		}
		if (resolution.localized()) {
			for (final Locale locale : locales) {
				addFromReferencedEntityAttributeForLocale(
					histogramName, locale, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target, scope
				);
			}
		} else {
			addFromReferencedEntityAttributeForLocale(
				histogramName, null, resolution, histogramShouldBeIndexed,
				affected, rtei, isGrouped, target, scope
			);
		}
	}

	/**
	 * Inserts histogram values sourced from a referenced entity attribute for a single locale
	 * (or `null` for non-localized). Scans the source collection's `FilterIndex` and applies
	 * default values for missing referenced entities.
	 *
	 * @param histogramName            name of the histogram definition
	 * @param locale                   locale for the source FilterIndex, or `null` for non-localized
	 * @param resolution               value resolution metadata
	 * @param histogramShouldBeIndexed owner PKs that must have a histogram value
	 * @param affected                 resolved affected groups
	 * @param rtei                     the top-level referenced-type index
	 * @param isGrouped                `true` when the reference has a group type
	 * @param target                   access to cross-entity filter indexes
	 */
	private static void addFromReferencedEntityAttributeForLocale(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull Scope scope
	) {
		final String sourceEntityType = Objects.requireNonNull(resolution.sourceEntityType());
		final FilterIndex sourceFilterIndex = target.getSourceFilterIndex(
			sourceEntityType, resolution.sourceAttributeName(), locale, scope
		);
		if (sourceFilterIndex == null) {
			if (log.isDebugEnabled()) {
				log.debug(
					"Source FilterIndex for entity `{}` attr `{}` locale `{}` not found -- skipping histogram.",
					sourceEntityType, resolution.sourceAttributeName(), locale
				);
			}
		} else {
			final Class<? extends Serializable> plainType = resolution.plainType();
			final boolean rangeSource = resolution.innerNumericType() != null;
			final PersistentRoaringBitmap shouldBeIndexedBitmap =
				getRoaringBitmap(histogramShouldBeIndexed);
			final ValueToRecord[] sourceBuckets =
				sourceFilterIndex.getHistogramOfAllRecords().getBuckets();
			// Track which referenced entity PKs were matched in at least one bucket so that defaults can be applied.
			final RoaringBitmapWriter<PersistentRoaringBitmap> encounteredRefPKsWriter = buildWriter();

			for (final ValueToRecord sourceBucket : sourceBuckets) {
				final Serializable emittedValue = resolveEmittedBucketValue(
					sourceBucket.getValue(), rangeSource, plainType, resolution.sourceAttributeName()
				);
				if (emittedValue == null) {
					continue;
				}
				final PersistentRoaringBitmap refPKsInBucket = getRoaringBitmap(sourceBucket.getRecordIds());

				for (final AffectedReferenceGroup group : affected.groups()) {
					// Check whether this group's referenced entity is present in the current source bucket.
					if (refPKsInBucket.contains(group.referencedEntityPK())) {
						encounteredRefPKsWriter.add(group.referencedEntityPK());
						final PersistentRoaringBitmap ownerPKs = getRoaringBitmap(group.ownerPKs());
						// Intersect "should be indexed" with the group's owner PKs
						// to avoid touching unrelated entities.
						final PersistentRoaringBitmap matched = and(shouldBeIndexedBitmap, ownerPKs);
						for (int ownerPK : matched) {
							insertHistogramValue(
								histogramName, locale, emittedValue, ownerPK, group, rtei, isGrouped,
								target, plainType, resolution.indexedDecimalPlaces()
							);
						}
					}
				}
			}

			// default values for missing referenced entities (already typed to plainType at build time)
			final Number defaultValue = resolution.defaultValue();
			if (defaultValue != null) {
				final PersistentRoaringBitmap encounteredRefPKs = encounteredRefPKsWriter.get();
				for (final AffectedReferenceGroup group : affected.groups()) {
					// Skip groups whose referenced entity was already found in at least one source bucket.
					if (!encounteredRefPKs.contains(group.referencedEntityPK())) {
						final PersistentRoaringBitmap ownerPKs = getRoaringBitmap(group.ownerPKs());
						final PersistentRoaringBitmap matched = and(shouldBeIndexedBitmap, ownerPKs);
						for (int ownerPK : matched) {
							insertHistogramValue(
								histogramName, locale, defaultValue, ownerPK, group, rtei, isGrouped,
								target, plainType, resolution.indexedDecimalPlaces()
							);
						}
					}
				}
			}
		}
	}

	/**
	 * Inserts histogram values sourced from a **reference-level attribute**. For grouped references
	 * each group's reduced index holds its own `FilterIndex`; for ungrouped, a single RTEI index is used.
	 * For localized attributes, performs the insert for each locale separately.
	 *
	 * @param histogramName            name of the histogram definition
	 * @param resolution               value resolution metadata
	 * @param histogramShouldBeIndexed owner PKs that must have a histogram value
	 * @param affected                 resolved affected groups
	 * @param rtei                     the top-level referenced-type index
	 * @param isGrouped                `true` when the reference has a group type
	 * @param target                   access to entity collection indexes
	 * @param referenceName            name of the reference
	 * @param locales                  locales for localized histograms; empty set for non-localized
	 */
	private static void addFromReferenceAttribute(
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		@Nonnull Set<Locale> locales
	) {
		if (resolution.localized()) {
			for (final Locale locale : locales) {
				addFromReferenceAttributeForLocale(
					histogramName, locale, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target, referenceName
				);
			}
		} else {
			addFromReferenceAttributeForLocale(
				histogramName, null, resolution, histogramShouldBeIndexed,
				affected, rtei, isGrouped, target, referenceName
			);
		}
	}

	/**
	 * Inserts histogram values from a reference-level attribute for a single locale (or `null`
	 * for non-localized). For grouped references each group's reduced index holds its own
	 * `FilterIndex`; for ungrouped, a single RTEI index is used.
	 *
	 * @param histogramName            name of the histogram definition
	 * @param locale                   locale for the reference attribute FilterIndex, or `null`
	 * @param resolution               value resolution metadata
	 * @param histogramShouldBeIndexed owner PKs that must have a histogram value
	 * @param affected                 resolved affected groups
	 * @param rtei                     the top-level referenced-type index
	 * @param isGrouped                `true` when the reference has a group type
	 * @param target                   access to entity collection indexes
	 * @param referenceName            name of the reference
	 */
	private static void addFromReferenceAttributeForLocale(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName
	) {
		final PersistentRoaringBitmap shouldBeIndexedBitmap = getRoaringBitmap(histogramShouldBeIndexed);
		final String sourceAttrName = resolution.sourceAttributeName();
		final AttributeIndexKey attrKey = new AttributeIndexKey(referenceName, sourceAttrName, locale);

		if (isGrouped) {
			// For grouped references, the reference attribute FilterIndex is partitioned per group:
			// each ReducedGroupEntityIndex holds its own per-reference attribute index.
			for (final AffectedReferenceGroup group : affected.groups()) {
				if (group.groupPK() != null) {
					final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
					for (int storagePK : storagePKs) {
						final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
							target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
						);
						processRefAttrFilterIndex(
							histogramName, locale, resolution, rgei.getFilterIndex(attrKey),
							shouldBeIndexedBitmap, group, rtei, true, target
						);
					}
				}
			}
		} else {
			// For ungrouped references, a single FilterIndex on the RTEI covers all owner entities.
			final FilterIndex refAttrFilterIndex = rtei.getFilterIndex(attrKey);
			for (final AffectedReferenceGroup group : affected.groups()) {
				processRefAttrFilterIndex(
					histogramName, locale, resolution, refAttrFilterIndex,
					shouldBeIndexedBitmap, group, rtei, false, target
				);
			}
		}
	}

	/**
	 * Processes a single reference-attribute `FilterIndex` for one group, inserting histogram values
	 * for eligible owner PKs. Falls back to `defaultValue` when the filter index is `null` or when
	 * an owner PK has no matching bucket.
	 *
	 * @param histogramName          name of the histogram definition
	 * @param locale                 locale for the histogram index, or `null` for non-localized
	 * @param resolution             value resolution metadata
	 * @param filterIndex            the `FilterIndex` for the reference attribute, or `null`
	 * @param shouldBeIndexedBitmap  bitmap of eligible owner PKs
	 * @param group                  the group being processed
	 * @param rtei                   the top-level referenced-type index
	 * @param isGrouped              `true` when the reference has a group type
	 * @param target                 access to entity collection indexes
	 */
	private static void processRefAttrFilterIndex(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nullable FilterIndex filterIndex,
		@Nonnull PersistentRoaringBitmap shouldBeIndexedBitmap,
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target
	) {
		final Class<? extends Serializable> plainType = resolution.plainType();
		processRefAttrFilterIndexBuckets(
			resolution, filterIndex, shouldBeIndexedBitmap, group,
			(value, ownerPK) -> insertHistogramValue(
				histogramName, locale, value, ownerPK, group, rtei, isGrouped,
				target, plainType, resolution.indexedDecimalPlaces()
			)
		);
	}

	/**
	 * Shared bucket iteration logic for reference-attribute FilterIndex processing. Iterates over
	 * histogram buckets, computes the triple intersection (eligible PKs, group owner PKs, bucket PKs),
	 * and invokes the given action for each matched (value, ownerPK) pair. Falls back to
	 * {@link HistogramValueDescriptor#defaultValue()} for eligible PKs that have no matching bucket.
	 *
	 * For range-typed source attributes (signalled by
	 * {@link HistogramValueDescriptor#innerNumericType()} being non-null), each bucket's value is a
	 * raw `Range` instance (sourced from the `InvertedIndex` shadow of the source `FilterIndex`); the
	 * action receives the `Range` directly. For scalar numeric sources the action receives a `Number`.
	 * The descriptor's `plainType` is the authoritative source of truth and any type mismatch surfaces
	 * as a defensive-design throw.
	 *
	 * @param resolution   value resolution metadata
	 * @param filterIndex  the FilterIndex for the reference attribute, or `null`
	 * @param eligiblePKs  bitmap of owner PKs to process
	 * @param group        the group being processed
	 * @param action       callback invoked for each (value, ownerPK) pair; the value type matches
	 *                     {@link HistogramValueDescriptor#plainType()}
	 */
	private static void processRefAttrFilterIndexBuckets(
		@Nonnull HistogramValueDescriptor resolution,
		@Nullable FilterIndex filterIndex,
		@Nonnull PersistentRoaringBitmap eligiblePKs,
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ObjIntConsumer<Serializable> action
	) {
		final PersistentRoaringBitmap ownerPKs = getRoaringBitmap(group.ownerPKs());
		final boolean rangeSource = resolution.innerNumericType() != null;
		final Class<? extends Serializable> plainType = resolution.plainType();
		// both `eligiblePKs` and `ownerPKs` are loop-invariant within this method —
		// intersect once and reuse across every bucket and the default-fill block
		final PersistentRoaringBitmap eligibleOwnerPKs = and(eligiblePKs, ownerPKs);
		if (filterIndex == null) {
			final Number defaultValue = resolution.defaultValue();
			if (defaultValue != null) {
				for (int ownerPK : eligibleOwnerPKs) {
					action.accept(defaultValue, ownerPK);
				}
			}
		} else {
			final ValueToRecord[] buckets = filterIndex.getHistogramOfAllRecords().getBuckets();
			final PersistentRoaringBitmap encountered = new PersistentRoaringBitmap();

			for (final ValueToRecord bucket : buckets) {
				final Serializable emittedValue = resolveEmittedBucketValue(
					bucket.getValue(), rangeSource, plainType, resolution.sourceAttributeName()
				);
				if (emittedValue == null) {
					// non-Range scalar histograms only accept Number buckets; anything else is a value
					// the source FilterIndex shouldn't have produced for this attribute
					continue;
				}
				final PersistentRoaringBitmap bucketPKs = getRoaringBitmap(bucket.getRecordIds());
				final PersistentRoaringBitmap matched = and(eligibleOwnerPKs, bucketPKs);
				if (!matched.isEmpty()) {
					encountered.or(matched);
					for (int ownerPK : matched) {
						action.accept(emittedValue, ownerPK);
					}
				}
			}

			// defaults are scalar-only by construction — HistogramValueDescriptor enforces
			// `defaultValue == null` for range-typed plainType
			final Number defaultValue = resolution.defaultValue();
			if (defaultValue != null) {
				final PersistentRoaringBitmap missing = andNot(eligibleOwnerPKs, encountered);
				for (int ownerPK : missing) {
					action.accept(defaultValue, ownerPK);
				}
			}
		}
	}

	/**
	 * Removes histogram values sourced from a **reference-level attribute** for the given owner PKs.
	 * Mirrors {@link #addFromReferenceAttribute} — reads the same reference attribute FilterIndex
	 * on RGEI (grouped) or RTEI (ungrouped) and calls {@link #removeHistogramValue} instead of
	 * {@link #insertHistogramValue}.
	 */
	private static void removeFromReferenceAttribute(
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		@Nonnull Set<Locale> locales
	) {
		if (!ownerPKsToRemove.isEmpty()) {
			if (resolution.localized()) {
				for (final Locale locale : locales) {
					removeFromReferenceAttributeForLocale(
						histogramName, locale, resolution, ownerPKsToRemove,
						affected, rtei, isGrouped, target, referenceName
					);
				}
			} else {
				removeFromReferenceAttributeForLocale(
					histogramName, null, resolution, ownerPKsToRemove,
					affected, rtei, isGrouped, target, referenceName
				);
			}
		}
	}

	/**
	 * Removes histogram values from a reference-level attribute for a single locale. Mirrors
	 * {@link #addFromReferenceAttributeForLocale} — reads the same FilterIndex and routes through
	 * grouped/ungrouped paths identically.
	 */
	private static void removeFromReferenceAttributeForLocale(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName
	) {
		final PersistentRoaringBitmap removeBitmap = getRoaringBitmap(ownerPKsToRemove);
		final String sourceAttrName = resolution.sourceAttributeName();
		final AttributeIndexKey attrKey = new AttributeIndexKey(referenceName, sourceAttrName, locale);

		if (isGrouped) {
			for (final AffectedReferenceGroup group : affected.groups()) {
				if (group.groupPK() != null) {
					final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
					for (int storagePK : storagePKs) {
						final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
							target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
						);
						processRefAttrFilterIndexForRemoval(
							histogramName, locale, resolution, rgei.getFilterIndex(attrKey),
							removeBitmap, group, rtei, true, target
						);
					}
				}
			}
		} else {
			final FilterIndex refAttrFilterIndex = rtei.getFilterIndex(attrKey);
			for (final AffectedReferenceGroup group : affected.groups()) {
				processRefAttrFilterIndexForRemoval(
					histogramName, locale, resolution, refAttrFilterIndex,
					removeBitmap, group, rtei, false, target
				);
			}
		}
	}

	/**
	 * Processes a single reference-attribute `FilterIndex` for one group, removing histogram values
	 * for the given owner PKs. Mirrors {@link #processRefAttrFilterIndex} — handles null FilterIndex,
	 * default values, non-numeric bucket values identically.
	 */
	private static void processRefAttrFilterIndexForRemoval(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nullable FilterIndex filterIndex,
		@Nonnull PersistentRoaringBitmap ownerPKsToRemove,
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target
	) {
		processRefAttrFilterIndexBuckets(
			resolution, filterIndex, ownerPKsToRemove, group,
			(value, ownerPK) -> removeHistogramValue(
				histogramName, locale, value, ownerPK, group, rtei, isGrouped, target,
				resolution.indexedDecimalPlaces()
			)
		);
	}

	/**
	 * Removes a histogram value from the RTEI and (for grouped references) from each matching
	 * {@link ReducedGroupEntityIndex}. Mirrors {@link #insertHistogramValue}. Skips removal
	 * when the histogram index does not exist or does not contain the (value, ownerPK) pair
	 * (the entry was never created — e.g. condition was false, or entity is in a different scope).
	 */
	private static void removeHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value, int ownerPK,
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		int indexedDecimalPlaces
	) {
		if (isGrouped && group.groupPK() != null) {
			final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
			for (int storagePK : storagePKs) {
				final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
					target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
				);
				if (histogramContainsOwner(rgei.getHistogramIndex(histogramName), locale, value, ownerPK)) {
					rgei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
				}
			}
		}
		if (histogramContainsOwner(rtei.getHistogramIndex(histogramName), locale, value, ownerPK)) {
			rtei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
		}
	}

	/**
	 * Checks whether the histogram index contains the given (value, ownerPK) pair. Returns false when
	 * the histogram index is null, the FilterIndex for the locale is null, or the specific value bucket
	 * does not contain the ownerPK.
	 *
	 * Membership is resolved by a direct single-bucket lookup —
	 * `getInvertedIndex().getRecordsEqualTo(normalizedValue)` fetches only the bucket keyed by the probe
	 * value (an empty bitmap on miss) instead of scanning every bucket. The probe `value` is first
	 * canonicalized through the histogram index's own normalizer (the same key form the bucket stores —
	 * e.g. a scaled `Integer` for a `BigDecimal` value type), so a raw probe matches its scaled /
	 * re-encoded stored counterpart.
	 */
	private static boolean histogramContainsOwner(
		@Nullable HistogramIndex histogramIndex,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		if (histogramIndex == null) {
			return false;
		}
		final FilterIndex filterIndex = histogramIndex.getFilterIndex(locale);
		if (filterIndex == null) {
			return false;
		}
		// buckets store the canonicalized key (e.g. a scaled Integer for a BigDecimal value type), so the raw
		// probe must be normalized through the same path before the lookup
		final Serializable normalizedValue = histogramIndex.normalizeValue(value);
		// direct O(log n) tree lookup for the single bucket keyed by the normalized value - avoids materializing the
		// entire histogram just to test membership in one bucket (getRecordsEqualTo returns an empty bitmap on miss)
		return filterIndex.getInvertedIndex().getRecordsEqualTo(normalizedValue).contains(ownerPK);
	}

	/**
	 * Inserts a histogram value into the RTEI and (for grouped references) into each matching
	 * {@link ReducedGroupEntityIndex}.
	 *
	 * @param histogramName  name of the histogram definition
	 * @param locale         locale for the histogram index, or `null` for non-localized
	 * @param value          the value to insert (a `Number` for plain numeric attributes or a `Range`
	 *                       instance for Range-typed attributes)
	 * @param ownerPK        primary key of the owner entity
	 * @param group          the group for reduced-index routing
	 * @param rtei           the top-level referenced-type index
	 * @param isGrouped            `true` when the reference has a group type
	 * @param target               access to entity collection indexes
	 * @param valueType            the plain type of the attribute
	 * @param indexedDecimalPlaces the source attribute schema's indexed decimal places, threaded to the
	 *                             histogram-index write boundary for scale normalization
	 */
	private static void insertHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		if (isGrouped && group.groupPK() != null) {
			final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
			for (int storagePK : storagePKs) {
				final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
					target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
				);
				rgei.insertHistogramValue(histogramName, locale, value, ownerPK, valueType, indexedDecimalPlaces);
			}
		}
		rtei.insertHistogramValue(histogramName, locale, value, ownerPK, valueType, indexedDecimalPlaces);
	}

	/**
	 * Dispatches to the appropriate resolution method based on {@link DependencyType}.
	 *
	 * @param target   access to entity collection indexes and schema
	 * @param mutation the cross-entity re-evaluation signal
	 * @return structured resolution; never null, may be {@link AffectedEntityResolution#EMPTY}
	 */
	@Nonnull
	private static AffectedEntityResolution resolveAffected(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateExpressionMutation mutation
	) {
		return switch (mutation.dependencyType()) {
			case GROUP_ENTITY_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE ->
				resolveForGroupEntityAttribute(target, mutation);
			case REFERENCED_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_REFERENCE_ATTRIBUTE ->
				resolveForReferencedEntityAttribute(target, mutation);
			case PARENT_ENTITY_ATTRIBUTE, PARENT_ENTITY_REFERENCE_ATTRIBUTE ->
				resolveForParentEntityAttribute(target, mutation);
		};
	}

	/**
	 * Resolves affected owner PKs when the mutated entity is the **reference group** entity.
	 *
	 * @param target   access to entity collection indexes and schema
	 * @param mutation carries the mutated group entity PK, reference name, and scope
	 * @return structured resolution; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForGroupEntityAttribute(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateExpressionMutation mutation
	) {
		final ReferencedTypeEntityIndex rtei = findReferencedTypeEntityIndex(
			target, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, mutation.scope(), mutation.referenceName()
		);
		if (rtei == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final int[] storagePKs = rtei.getAllReferenceIndexes(mutation.mutatedEntityPK());
		if (storagePKs.length == 0) {
			return AffectedEntityResolution.EMPTY;
		}
		final int groupPK = mutation.mutatedEntityPK();
		// Pre-allocate with double capacity since each group may contain multiple referenced entities.
		final List<AffectedReferenceGroup> groups = new ArrayList<>(storagePKs.length << 1);
		for (int storagePK : storagePKs) {
			final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
				target.getIndexByPrimaryKeyIfExists(storagePK), storagePK
			);
			final Set<Integer> refPKs = rgei.getReferencedEntityPrimaryKeys();
			for (int refPK : refPKs) {
				final Bitmap ownerPKs = rgei.getOwnerPKsForReferencedEntity(refPK);
				if (ownerPKs != null && !ownerPKs.isEmpty()) {
					groups.add(new AffectedReferenceGroup(refPK, groupPK, ownerPKs));
				}
			}
		}
		return new AffectedEntityResolution(groups);
	}

	/**
	 * Resolves affected owner PKs when the mutated entity is the **referenced entity**.
	 * Dispatches to grouped or ungrouped variant based on the reference schema.
	 *
	 * @param target   access to entity collection indexes and schema
	 * @param mutation carries the mutated referenced entity PK, reference name, and scope
	 * @return structured resolution; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForReferencedEntityAttribute(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateExpressionMutation mutation
	) {
		final int refEntityPK = mutation.mutatedEntityPK();
		final String refName = mutation.referenceName();
		final Scope scope = mutation.scope();
		final ReferenceSchemaContract refSchema = target.getEntitySchema()
			.getReference(refName).orElseThrow();
		if (refSchema.getReferencedGroupType() != null) {
			return resolveForRefEntityAttrGrouped(target, refName, refEntityPK, scope);
		}
		return resolveForRefEntityAttrUngrouped(target, refName, refEntityPK, scope);
	}

	/**
	 * Resolves owner PKs for a **grouped** reference when the mutated entity is the referenced entity.
	 *
	 * @param target          access to entity collection indexes
	 * @param referenceName   name of the reference
	 * @param refEntityPK     PK of the mutated referenced entity
	 * @param scope           scope of the entity collection
	 * @return structured resolution; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForRefEntityAttrGrouped(
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		int refEntityPK,
		@Nonnull Scope scope
	) {
		final ReferencedTypeEntityIndex groupRtei = findReferencedTypeEntityIndex(
			target, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
		);
		if (groupRtei == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final Set<Integer> allGroupPKs = groupRtei.getAllTrackedReferencedEntityPrimaryKeys();
		if (allGroupPKs.isEmpty()) {
			return AffectedEntityResolution.EMPTY;
		}
		final List<AffectedReferenceGroup> groups = new ArrayList<>(4);
		for (int groupPK : allGroupPKs) {
			final int[] storagePKs = groupRtei.getAllReferenceIndexes(groupPK);
			for (int storagePK : storagePKs) {
				final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
					target.getIndexByPrimaryKeyIfExists(storagePK), storagePK
				);
				final Bitmap ownerPKs = rgei.getOwnerPKsForReferencedEntity(refEntityPK);
				if (ownerPKs != null && !ownerPKs.isEmpty()) {
					groups.add(new AffectedReferenceGroup(refEntityPK, groupPK, ownerPKs));
				}
			}
		}
		return new AffectedEntityResolution(groups);
	}

	/**
	 * Resolves owner PKs for an **ungrouped** reference when the mutated entity is the referenced entity.
	 *
	 * @param target          access to entity collection indexes
	 * @param referenceName   name of the reference
	 * @param refEntityPK     PK of the mutated referenced entity
	 * @param scope           scope of the entity collection
	 * @return structured resolution; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForRefEntityAttrUngrouped(
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		int refEntityPK,
		@Nonnull Scope scope
	) {
		final ReferencedTypeEntityIndex rtei = findReferencedTypeEntityIndex(
			target, EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
		);
		if (rtei == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final int[] storagePKs = rtei.getAllReferenceIndexes(refEntityPK);
		if (storagePKs.length > 0) {
			// entity component indexes exist — use reduced entity indexes to find owner PKs
			final List<AffectedReferenceGroup> groups = new ArrayList<>(storagePKs.length);
			for (int storagePK : storagePKs) {
				final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(storagePK);
				if (reducedIndex != null) {
					final Bitmap ownerPKs = reducedIndex.getAllPrimaryKeys();
					if (!ownerPKs.isEmpty()) {
						groups.add(new AffectedReferenceGroup(refEntityPK, null, ownerPKs));
					}
				}
			}
			return new AffectedEntityResolution(groups);
		}
		// entity component indexes not enabled — fall back to global facet index which
		// stores per-facetId (= refEntityPK) bitmaps of owner PKs
		final EntityIndex globalIndex = target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final FacetReferenceIndex facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
		if (facetRefIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		if (notGrouped == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(refEntityPK);
		if (facetIdIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final Bitmap ownerPKs = facetIdIndex.getRecords();
		if (ownerPKs.isEmpty()) {
			return AffectedEntityResolution.EMPTY;
		}
		return new AffectedEntityResolution(
			List.of(new AffectedReferenceGroup(refEntityPK, null, ownerPKs))
		);
	}

	/**
	 * Resolves affected owner PKs when the mutated entity is the **parent** entity. Retrieves all
	 * hierarchy descendants and dispatches to grouped/ungrouped variant.
	 *
	 * @param target   access to entity collection indexes and schema
	 * @param mutation carries the mutated parent entity PK, reference name, and scope
	 * @return structured resolution restricted to the parent's children; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForParentEntityAttribute(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateExpressionMutation mutation
	) {
		final int parentPK = mutation.mutatedEntityPK();
		final String refName = mutation.referenceName();
		final Scope scope = mutation.scope();
		final EntityIndex globalIndex = target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}
		// Depth 0 means "all descendants at any level under parentPK".
		final Bitmap childrenPKs = globalIndex.listHierarchyNodesFromParentDownTo(
			parentPK, 0, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE
		);
		if (childrenPKs.isEmpty()) {
			return AffectedEntityResolution.EMPTY;
		}
		final ReferenceSchemaContract refSchema = target.getEntitySchema()
			.getReference(refName).orElseThrow();
		if (refSchema.getReferencedGroupType() != null) {
			return resolveForParentGrouped(target, refName, childrenPKs, scope);
		}
		return resolveForParentUngrouped(target, refName, childrenPKs, scope);
	}

	/**
	 * Resolves owner PKs for a **grouped** reference when the mutated entity is the parent.
	 * Intersects each group's owner PKs with the hierarchy children bitmap.
	 *
	 * @param target          access to entity collection indexes
	 * @param referenceName   name of the reference
	 * @param childrenPKs     bitmap of all hierarchy descendants of the mutated parent
	 * @param scope           scope of the entity collection
	 * @return structured resolution restricted to children of the parent; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForParentGrouped(
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		@Nonnull Bitmap childrenPKs,
		@Nonnull Scope scope
	) {
		final ReferencedTypeEntityIndex groupRtei = findReferencedTypeEntityIndex(
			target, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
		);
		if (groupRtei == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final Set<Integer> allGroupPKs = groupRtei.getAllTrackedReferencedEntityPrimaryKeys();
		if (allGroupPKs.isEmpty()) {
			return AffectedEntityResolution.EMPTY;
		}
		final List<AffectedReferenceGroup> groups = new ArrayList<>(4);
		final PersistentRoaringBitmap childrenRoaring = getRoaringBitmap(childrenPKs);
		for (int groupPK : allGroupPKs) {
			final int[] storagePKs = groupRtei.getAllReferenceIndexes(groupPK);
			for (int storagePK : storagePKs) {
				final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
					target.getIndexByPrimaryKeyIfExists(storagePK), storagePK
				);
				final Set<Integer> refPKs = rgei.getReferencedEntityPrimaryKeys();
				for (int refPK : refPKs) {
					final Bitmap ownerPKs = rgei.getOwnerPKsForReferencedEntity(refPK);
					if (ownerPKs != null && !ownerPKs.isEmpty()) {
						// Intersect the group's owner PKs with the hierarchy children bitmap;
						// only owners that are actually children of the mutated parent are relevant.
						final Bitmap intersected = new BaseBitmap(
							and(
								childrenRoaring,
								getRoaringBitmap(ownerPKs)
							)
						);
						if (!intersected.isEmpty()) {
							groups.add(new AffectedReferenceGroup(refPK, groupPK, intersected));
						}
					}
				}
			}
		}
		return new AffectedEntityResolution(groups);
	}

	/**
	 * Resolves owner PKs for an **ungrouped** reference when the mutated entity is the parent.
	 * Intersects each reduced index's owner PKs with the hierarchy children bitmap.
	 *
	 * @param target          access to entity collection indexes
	 * @param referenceName   name of the reference
	 * @param childrenPKs     bitmap of all hierarchy descendants of the mutated parent
	 * @param scope           scope of the entity collection
	 * @return structured resolution restricted to children of the parent; never null
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForParentUngrouped(
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		@Nonnull Bitmap childrenPKs,
		@Nonnull Scope scope
	) {
		final ReferencedTypeEntityIndex rtei = findReferencedTypeEntityIndex(
			target, EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
		);
		if (rtei == null) {
			return AffectedEntityResolution.EMPTY;
		}
		final Set<Integer> allRefPKs = rtei.getAllTrackedReferencedEntityPrimaryKeys();
		if (allRefPKs.isEmpty()) {
			return AffectedEntityResolution.EMPTY;
		}
		final List<AffectedReferenceGroup> groups = new ArrayList<>(allRefPKs.size());
		final PersistentRoaringBitmap childrenRoaring = getRoaringBitmap(childrenPKs);
		for (int refPK : allRefPKs) {
			final int[] storagePKs = rtei.getAllReferenceIndexes(refPK);
			for (int storagePK : storagePKs) {
				final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(storagePK);
				if (reducedIndex == null) {
					continue;
				}
				final Bitmap ownerPKs = reducedIndex.getAllPrimaryKeys();
				if (!ownerPKs.isEmpty()) {
					// Intersect the reduced index's owner PKs with the hierarchy children bitmap;
					// only owners that are actually children of the mutated parent are relevant.
					final Bitmap intersected = new BaseBitmap(
						and(
							childrenRoaring,
							getRoaringBitmap(ownerPKs)
						)
					);
					if (!intersected.isEmpty()) {
						groups.add(new AffectedReferenceGroup(refPK, null, intersected));
					}
				}
			}
		}
		return new AffectedEntityResolution(groups);
	}

	/**
	 * Injects a PK-scoping constraint into the trigger's `FilterBy` to restrict the query to the
	 * single mutated entity. For parent dependencies the filter is returned unchanged (scoping is
	 * handled by the resolution step).
	 *
	 * @param triggerFilterBy   the pre-translated `FilterBy` from the trigger
	 * @param referenceName     name of the reference whose `referenceHaving` clause receives the PK scope
	 * @param mutatedEntityPK   PK of the mutated entity
	 * @param dependencyType    relationship between the mutated entity and the owner entity
	 * @return a new `FilterBy` with PK-scoping injected; unchanged for parent deps
	 */
	@Nonnull
	private static FilterBy parameterize(
		@Nonnull FilterBy triggerFilterBy,
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType
	) {
		// Parent entity dependency does not require PK scoping: the children bitmap from the resolution step
		// already limits the scope to the right owners. Returning the original avoids an unnecessary allocation.
		if (dependencyType == DependencyType.PARENT_ENTITY_ATTRIBUTE
			|| dependencyType == DependencyType.PARENT_ENTITY_REFERENCE_ATTRIBUTE) {
			return triggerFilterBy;
		}
		// The raw PK constraint to inject into the appropriate scope container.
		final EntityPrimaryKeyInSet pkConstraint = new EntityPrimaryKeyInSet(mutatedEntityPK);
		final boolean isGroupScope = dependencyType == DependencyType.GROUP_ENTITY_ATTRIBUTE
			|| dependencyType == DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE;
		return rewriteMatchingReferenceHavings(triggerFilterBy, referenceName, pkConstraint, isGroupScope);
	}

	/**
	 * Rewrites every owner-scope {@link ReferenceHaving} whose reference name matches `referenceName`
	 * anywhere in `filterBy` by passing it through {@link #injectPkScope}. The traversal is full-tree
	 * (handles `Or`, `And`, `Not`, and any other container the constraint model defines), so the
	 * rewrite reaches every owner-scope match regardless of nesting depth — not just top-level direct
	 * children of the {@link FilterBy}.
	 *
	 * The translator can emit nested `referenceHaving(otherRef, ...)` inside an enclosing
	 * `entityHaving(...)` / `groupHaving(...)` (paths `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` and
	 * `GROUP_ENTITY_REFERENCE_ATTRIBUTE`). Those inner clauses live in a *different* entity scope
	 * (the referenced entity, not the owner), so their PK constraints must NOT be merged with the
	 * owner-scope mutation's PK. The guard `!visitor.isWithin(EntityHaving.class)
	 * && !visitor.isWithin(GroupHaving.class)` skips such inner clauses even when they happen to
	 * carry the same `referenceName` as the owner-scope reference (e.g. self-referencing schemas).
	 *
	 * @param filterBy       the filter to rewrite
	 * @param referenceName  the reference whose `ReferenceHaving` instances receive the PK scope
	 * @param pkConstraint   the PK constraint to merge into each matching `ReferenceHaving`
	 * @param isGroupScope   `true` for {@link GroupHaving}-scoped injection, `false` for
	 *                       {@link EntityHaving}-scoped injection
	 * @return the rewritten filter; structurally identical when no owner-scope `ReferenceHaving`
	 *         matched
	 */
	@Nonnull
	private static FilterBy rewriteMatchingReferenceHavings(
		@Nonnull FilterBy filterBy,
		@Nonnull String referenceName,
		@Nonnull EntityPrimaryKeyInSet pkConstraint,
		boolean isGroupScope
	) {
		final FilterBy rewritten = (FilterBy) ConstraintCloneVisitor.clone(
			filterBy,
			(visitor, constraint) -> constraint instanceof final ReferenceHaving rh
				&& rh.getReferenceName().equals(referenceName)
				&& !visitor.isWithin(EntityHaving.class)
				&& !visitor.isWithin(GroupHaving.class)
				? injectPkScope(rh, referenceName, pkConstraint, isGroupScope)
				: constraint
		);
		// ConstraintCloneVisitor.clone is @Nullable because it returns null when the cloned tree
		// collapses to a non-applicable constraint. That cannot happen here: the trigger `filterBy`
		// always carries an applicable `referenceHaving`, and the translator only injects PK scope —
		// it never drops children — so the clone is at least as applicable as the input. Assert the
		// invariant explicitly to keep the @Nonnull contract sound rather than propagate a silent null.
		return Objects.requireNonNull(
			rewritten,
			"Rewritten referenceHaving filter unexpectedly collapsed to null — the trigger `filterBy` " +
				"must always retain an applicable constraint after PK-scope injection."
		);
	}

	/**
	 * Injects the given PK constraint into the matching {@link ReferenceHaving} clause. Every existing
	 * {@link GroupHaving} (or {@link EntityHaving} for entity-scoped dependencies) child receives the
	 * PK constraint merged into its body, so a {@link ReferenceHaving} carrying multiple sibling
	 * scope containers (produced by the expression translator when the same reference declares more
	 * than one `groupEntity?.…` / `entity?.…` predicate) is correctly constrained on every branch.
	 * When no scope container is present, a new one wrapping the PK constraint is appended as an
	 * And-sibling.
	 *
	 * @param rh            the original referenceHaving clause
	 * @param referenceName the reference name for the new ReferenceHaving
	 * @param pkConstraint  the PK constraint to inject
	 * @param isGroupScope  `true` when the scope container is {@link GroupHaving}, `false` for
	 *                      {@link EntityHaving}
	 * @return a new {@link ReferenceHaving} with the PK constraint injected
	 */
	@Nonnull
	private static ReferenceHaving injectPkScope(
		@Nonnull ReferenceHaving rh,
		@Nonnull String referenceName,
		@Nonnull EntityPrimaryKeyInSet pkConstraint,
		boolean isGroupScope
	) {
		final FilterConstraint[] rhChildren = rh.getChildren();
		final FilterConstraint[] updatedChildren = new FilterConstraint[rhChildren.length];
		boolean scopeContainerFound = false;
		for (int j = 0; j < rhChildren.length; j++) {
			final FilterConstraint child = rhChildren[j];
			if (isGroupScope && child instanceof final GroupHaving existing) {
				updatedChildren[j] = new GroupHaving(new And(existing.getChild(), pkConstraint));
				scopeContainerFound = true;
			} else if (!isGroupScope && child instanceof final EntityHaving existing) {
				updatedChildren[j] = new EntityHaving(new And(existing.getChild(), pkConstraint));
				scopeContainerFound = true;
			} else {
				updatedChildren[j] = child;
			}
		}
		if (scopeContainerFound) {
			return updatedChildren.length == 1
				? new ReferenceHaving(referenceName, updatedChildren[0])
				: new ReferenceHaving(referenceName, new And(updatedChildren));
		}
		// No existing scope container — wrap PK in a new one and add as an And-sibling.
		final FilterConstraint pkScope = isGroupScope
			? new GroupHaving(pkConstraint)
			: new EntityHaving(pkConstraint);
		final FilterConstraint[] andChildren = new FilterConstraint[rhChildren.length + 1];
		System.arraycopy(rhChildren, 0, andChildren, 0, rhChildren.length);
		andChildren[rhChildren.length] = pkScope;
		return new ReferenceHaving(referenceName, new And(andChildren));
	}

	/**
	 * Looks up a {@link ReferencedTypeEntityIndex} by type, scope, and reference name.
	 * Returns {@code null} when the index does not exist.
	 *
	 * @param target        access to entity collection indexes
	 * @param type          the entity index type to look up
	 * @param scope         scope of the entity collection
	 * @param referenceName name of the reference
	 * @return the cast index, or {@code null} when not found
	 */
	@Nullable
	private static ReferencedTypeEntityIndex findReferencedTypeEntityIndex(
		@Nonnull IndexMutationTarget target,
		@Nonnull EntityIndexType type,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndex index = target.getIndexIfExists(new EntityIndexKey(type, scope, referenceName));
		return index instanceof ReferencedTypeEntityIndex rtei ? rtei : null;
	}

	/**
	 * Looks up a {@link ReferencedTypeEntityIndex} by type, scope, and reference name and registers it for
	 * modification tracking. Returns {@code null} when the index does not exist. Use this variant instead of
	 * {@link #findReferencedTypeEntityIndex} when the caller intends to mutate the returned index.
	 *
	 * @param target        access to entity collection indexes
	 * @param type          the entity index type to look up
	 * @param scope         scope of the entity collection
	 * @param referenceName name of the reference
	 * @return the cast index registered for modification, or {@code null} when not found
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex findReferencedTypeEntityIndexForModification(
		@Nonnull IndexMutationTarget target,
		@Nonnull EntityIndexType type,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndexKey key = new EntityIndexKey(type, scope, referenceName);
		return (ReferencedTypeEntityIndex) target.getOrCreateIndex(key);
	}

	/**
	 * Asserts that the given index is a {@link ReferencedTypeEntityIndex} and returns the cast.
	 *
	 * @param index   the entity index to check (may be {@code null})
	 * @param contextSupplier lazily evaluated description of the expected index location
	 * @return the cast index; never null
	 * @throws GenericEvitaInternalError if the index is {@code null} or not a {@link ReferencedTypeEntityIndex}
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex asReferencedTypeEntityIndex(
		@Nullable EntityIndex index,
		@Nonnull Supplier<String> contextSupplier
	) {
		if (!(index instanceof ReferencedTypeEntityIndex rtei)) {
			throw new GenericEvitaInternalError(
				"Expected ReferencedTypeEntityIndex for " + contextSupplier.get() +
					" but got " + (index == null ? "null" : index.getClass().getSimpleName())
			);
		}
		return rtei;
	}

	/**
	 * Asserts that the given index is a {@link ReducedGroupEntityIndex} and returns the cast.
	 *
	 * @param index     the entity index to check (may be {@code null})
	 * @param storagePK the storage primary key used for the lookup (included in the error message)
	 * @return the cast index; never null
	 * @throws GenericEvitaInternalError if the index is {@code null} or not a {@link ReducedGroupEntityIndex}
	 */
	@Nonnull
	private static ReducedGroupEntityIndex asReducedGroupEntityIndex(
		@Nullable EntityIndex index, int storagePK
	) {
		if (!(index instanceof ReducedGroupEntityIndex rgei)) {
			throw new GenericEvitaInternalError(
				"Expected ReducedGroupEntityIndex for storage PK " + storagePK +
					" but got " + (index == null ? "null" : index.getClass().getSimpleName())
			);
		}
		return rgei;
	}

	/**
	 * Lazy pull-iterator yielding {@link AffectedReferenceEntry} records for owner PKs that pass
	 * the filter bitmap. Pre-fetches via {@link #advance()} so {@link #hasNext()} is always O(1).
	 */
	private static class FilteredEntryIterator implements Iterator<AffectedReferenceEntry> {
		/** All resolved reference groups to iterate over. */
		private final List<AffectedReferenceGroup> groups;
		/** Membership bitmap — only owner PKs present in this bitmap are yielded. */
		private final PersistentRoaringBitmap filterBitmap;
		/** Index of the current group in {@link #groups} being iterated. */
		private int groupIdx;
		/** Current group's owner PK array, or {@code null} when not yet loaded / fully consumed. */
		@Nullable private int[] currentOwnerPKs;
		/** Position within {@link #currentOwnerPKs} for the current group. */
		private int ownerIdx;
		/** Referenced entity PK cached from the current group for entry construction. */
		private int currentRefEntityPK;
		/** Group PK cached from the current group for entry construction ({@code null} for ungrouped). */
		@Nullable private Integer currentGroupPK;
		/** Pre-fetched next entry, or {@code null} when exhausted. */
		@Nullable private AffectedReferenceEntry nextEntry;

		/**
		 * @param groups resolved reference groups to iterate over
		 * @param pks    membership filter bitmap
		 */
		FilteredEntryIterator(
			@Nonnull List<AffectedReferenceGroup> groups, @Nonnull Bitmap pks
		) {
			this.groups = groups;
			this.filterBitmap = getRoaringBitmap(pks);
			this.groupIdx = 0;
			this.ownerIdx = 0;
			this.currentOwnerPKs = null;
			advance();
		}

		/** {@inheritDoc} */
		@Override
		public boolean hasNext() {
			return this.nextEntry != null;
		}

		/** {@inheritDoc} */
		@Override
		public AffectedReferenceEntry next() {
			if (this.nextEntry == null) {
				throw new NoSuchElementException();
			}
			final AffectedReferenceEntry result = this.nextEntry;
			advance();
			return result;
		}

		/**
		 * Advances to the next matching `(group, ownerPK)` pair or sets `nextEntry` to `null` when exhausted.
		 */
		private void advance() {
			this.nextEntry = null;
			while (this.groupIdx < this.groups.size()) {
				if (this.currentOwnerPKs == null) {
					// Load the next group: cache its reference/group PKs and obtain the raw owner PK array.
					final AffectedReferenceGroup group = this.groups.get(this.groupIdx);
					this.currentRefEntityPK = group.referencedEntityPK();
					this.currentGroupPK = group.groupPK();
					this.currentOwnerPKs = group.ownerPKs().getArray();
					this.ownerIdx = 0;
				}
				while (this.ownerIdx < this.currentOwnerPKs.length) {
					final int ownerPK = this.currentOwnerPKs[this.ownerIdx++];
					if (this.filterBitmap.contains(ownerPK)) {
						this.nextEntry = new AffectedReferenceEntry(
							this.currentRefEntityPK, this.currentGroupPK, ownerPK
						);
						return;
					}
				}
				// Current group exhausted — signal that the next iteration of the outer loop should load a new group.
				this.currentOwnerPKs = null;
				this.groupIdx++;
			}
		}
	}
}
