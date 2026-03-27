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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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
			final RoaringBitmap[] bitmaps = new RoaringBitmap[this.groups.size()];
			for (int i = 0; i < this.groups.size(); i++) {
				bitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(this.groups.get(i).ownerPKs());
			}
			return new BaseBitmap(RoaringBitmap.or(bitmaps));
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
	 * Functional interface for removing a histogram value from an entity index. Used to abstract
	 * over the identical `removeHistogramValue` method signatures on {@link ReducedGroupEntityIndex}
	 * and {@link ReferencedTypeEntityIndex}, allowing a single scan-and-remove implementation.
	 */
	@FunctionalInterface
	private interface HistogramValueRemover {

		/**
		 * Removes the histogram value associated with the given owner entity PK.
		 *
		 * @param histogramName the name of the histogram definition
		 * @param locale        locale for localized histograms, or `null` for non-localized
		 * @param value         the histogram bucket value to remove
		 * @param ownerPK       the primary key of the owner entity
		 */
		void remove(
			@Nonnull String histogramName, @Nullable Locale locale,
			@Nonnull Serializable value, int ownerPK
		);
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
			final ExpressionIndexTrigger facetTrigger = target.getTrigger(
				mutation.referenceName(), mutation.dependencyType(), mutation.scope()
			);
			final Collection<HistogramExpressionTrigger> histogramTriggers = target.getHistogramTriggers(
				mutation.referenceName(), mutation.scope()
			);

			if (facetTrigger == null && histogramTriggers.isEmpty()) {
				return;
			}

			if (facetTrigger != null) {
				processFacetTrigger(facetTrigger, mutation, target, affected, allAffectedOwnerPKs);
			}

			if (!histogramTriggers.isEmpty()) {
				processHistogramTriggers(
					histogramTriggers, mutation, target, affected, allAffectedOwnerPKs
				);
			}
		}
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
		@Nonnull ExpressionIndexTrigger facetTrigger,
		@Nonnull ReevaluateExpressionMutation mutation,
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull Bitmap allAffectedOwnerPKs
	) {
		final FilterBy parameterizedFilter = parameterize(
			facetTrigger.getFilterByConstraint(), mutation.referenceName(),
			mutation.mutatedEntityPK(), mutation.dependencyType()
		);
		final Bitmap currentlyTruePKs = target.evaluateFilter(parameterizedFilter, mutation.scope());

		// Intersection: owner PKs that ARE affected AND for which the expression is now true → add facet.
		final Bitmap shouldBeFaceted = RoaringBitmapBackedBitmap.and(
			new RoaringBitmap[]{
				RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
				RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
			}
		);
		// Complement: owner PKs that ARE affected but for which the expression is now false → remove facet.
		final Bitmap shouldNotBeFaceted = new BaseBitmap(
			RoaringBitmap.andNot(
				RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
				RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
			)
		);

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

		for (AffectedReferenceEntry entry : affected.entriesForOwnerPKs(shouldBeFaceted)) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.referencedEntityPK());
			globalIndex.addFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			applyFacetToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, true);
		}
		for (AffectedReferenceEntry entry : affected.entriesForOwnerPKs(shouldNotBeFaceted)) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.referencedEntityPK());
			globalIndex.removeFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			applyFacetToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, false);
		}
	}

	/**
	 * Propagates a facet add/remove to every reduced index covering the given referenced entity.
	 * No-op when `refTypeIndex` is `null` (non-partitioned schemas).
	 *
	 * @param target        access to the entity collection's index store
	 * @param refTypeIndex  the `REFERENCED_ENTITY_TYPE` index, or `null` for non-partitioned schemas
	 * @param refSchema     schema of the reference being updated
	 * @param refKey        the `(referenceName, referencedEntityPK)` key
	 * @param entry         the `(referencedEntityPK, groupPK, ownerPK)` triple
	 * @param add           `true` to add, `false` to remove
	 */
	private static void applyFacetToReducedIndexes(
		@Nonnull IndexMutationTarget target,
		@Nullable ReferencedTypeEntityIndex refTypeIndex,
		@Nonnull ReferenceSchemaContract refSchema,
		@Nonnull ReferenceKey refKey,
		@Nonnull AffectedReferenceEntry entry,
		boolean add
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
			if (add) {
				reducedIndex.addFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			} else {
				reducedIndex.removeFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			}
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

			// unconditional triggers have no FilterBy — all affected PKs should be indexed
			final Bitmap histogramShouldBeIndexed;
			final Bitmap histogramShouldNotBeIndexed;
			if (trigger.getDependencyType() == null || trigger.getMutatedEntityType() == null) {
				// unconditional: all affected PKs are indexed, none are removed
				histogramShouldBeIndexed = allAffectedOwnerPKs;
				histogramShouldNotBeIndexed = new BaseBitmap();
			} else {
				// Conditional trigger: parameterize and evaluate the FilterBy to split affected PKs into two sets.
				final FilterBy parameterizedFilter = parameterize(
					trigger.getFilterByConstraint(), referenceName,
					mutation.mutatedEntityPK(), mutation.dependencyType()
				);
				final Bitmap histogramTruePKs = target.evaluateFilter(parameterizedFilter, scope);
				// Intersection: affected PKs for which the condition is now true → index them.
				histogramShouldBeIndexed = RoaringBitmapBackedBitmap.and(
					new RoaringBitmap[]{
						RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
						RoaringBitmapBackedBitmap.getRoaringBitmap(histogramTruePKs)
					}
				);
				// Complement: affected PKs for which the condition is now false → remove their entries.
				histogramShouldNotBeIndexed = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
						RoaringBitmapBackedBitmap.getRoaringBitmap(histogramTruePKs)
					)
				);
			}

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

			// remove stale entries
			removeHistogramEntries(
				histogramName, histogramShouldNotBeIndexed, affected, rtei, isGrouped, target
			);
			// remove-before-add for value updates: clear any existing value for "should be indexed" PKs
			// so that the new value (which may differ from the old one) is written cleanly without accumulation
			removeHistogramEntries(
				histogramName, histogramShouldBeIndexed, affected, rtei, isGrouped, target
			);
			// add current values
			addHistogramEntries(
				histogramName, trigger, histogramShouldBeIndexed, affected, rtei, isGrouped,
				target, referenceName, scope, locales
			);
		}
	}

	/**
	 * Removes histogram entries for `ownerPKsToRemove` from the RTEI and (for grouped references)
	 * from each relevant {@link ReducedGroupEntityIndex}. Iterates only the locales that actually
	 * have data in the histogram index, avoiding the need for external schema locale lookup.
	 *
	 * @param histogramName      name of the histogram definition
	 * @param ownerPKsToRemove   owner PKs whose histogram entries should be cleared
	 * @param affected           resolved affected groups
	 * @param rtei               the top-level referenced-type entity index
	 * @param isGrouped          `true` when the reference has a group type
	 * @param target             access to reduced indexes by storage PK
	 */
	private static void removeHistogramEntries(
		@Nonnull String histogramName,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped,
		@Nonnull IndexMutationTarget target
	) {
		if (ownerPKsToRemove.isEmpty()) {
			return;
		}
		if (isGrouped) {
			for (final AffectedReferenceGroup group : affected.groups()) {
				if (group.groupPK() != null) {
					final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
					for (int storagePK : storagePKs) {
						final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
							target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
						);
						final HistogramIndex histogramIndex = rgei.getHistogramIndex(histogramName);
						if (histogramIndex != null) {
							histogramIndex.forEachLocale((name, locale) ->
								scanAndRemoveHistogramEntries(
									histogramName, locale, ownerPKsToRemove,
									histogramIndex.getFilterIndex(locale),
									rgei::removeHistogramValue
								)
							);
						}
					}
				}
			}
		}
		// Always remove from the top-level RTEI histogram index regardless of grouping.
		final HistogramIndex rteiHistogram = rtei.getHistogramIndex(histogramName);
		if (rteiHistogram != null) {
			rteiHistogram.forEachLocale((name, locale) ->
				scanAndRemoveHistogramEntries(
					histogramName, locale, ownerPKsToRemove,
					rteiHistogram.getFilterIndex(locale),
					rtei::removeHistogramValue
				)
			);
		}
	}

	/**
	 * Scans histogram buckets and removes entries for owner PKs present in `ownerPKsToRemove`.
	 * Uses scan-then-intersect because `FilterIndex` has no reverse PK-to-value mapping.
	 *
	 * @param histogramName    name of the histogram
	 * @param locale           locale for the histogram index, or `null` for non-localized
	 * @param ownerPKsToRemove owner PKs to remove
	 * @param filterIndex      the filter index to scan, or `null` if not yet created
	 * @param remover          callback performing the actual removal on the target index
	 */
	private static void scanAndRemoveHistogramEntries(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Bitmap ownerPKsToRemove,
		@Nullable FilterIndex filterIndex,
		@Nonnull HistogramValueRemover remover
	) {
		if (filterIndex != null) {
			final RoaringBitmap removePKs = RoaringBitmapBackedBitmap.getRoaringBitmap(ownerPKsToRemove);
			final ValueToRecordBitmap[] buckets = filterIndex.getHistogramOfAllRecords().getHistogramBuckets();
			for (final ValueToRecordBitmap bucket : buckets) {
				// Intersect the PKs-to-remove with the PKs that currently carry this bucket value.
				final RoaringBitmap intersection = RoaringBitmap.and(
					removePKs, RoaringBitmapBackedBitmap.getRoaringBitmap(bucket.getRecordIds())
				);
				if (!intersection.isEmpty()) {
					final Serializable value = bucket.getValue();
					for (int ownerPK : intersection) {
						remover.remove(histogramName, locale, value, ownerPK);
					}
				}
			}
		}
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
		@Nonnull String histogramName, @Nonnull HistogramExpressionTrigger trigger,
		@Nonnull Bitmap histogramShouldBeIndexed, @Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei, boolean isGrouped,
		@Nonnull IndexMutationTarget target, @Nonnull String referenceName, @Nonnull Scope scope,
		@Nonnull Set<Locale> locales
	) {
		if (!histogramShouldBeIndexed.isEmpty()) {
			final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
			if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
				addFromReferencedEntityAttribute(
					histogramName, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target, locales
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
		@Nonnull Set<Locale> locales
	) {
		final String sourceEntityType = resolution.sourceEntityType();
		if (sourceEntityType == null) {
			return;
		}
		if (resolution.localized()) {
			for (final Locale locale : locales) {
				addFromReferencedEntityAttributeForLocale(
					histogramName, locale, resolution, histogramShouldBeIndexed,
					affected, rtei, isGrouped, target
				);
			}
		} else {
			addFromReferencedEntityAttributeForLocale(
				histogramName, null, resolution, histogramShouldBeIndexed,
				affected, rtei, isGrouped, target
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
		@Nonnull IndexMutationTarget target
	) {
		final String sourceEntityType = Objects.requireNonNull(resolution.sourceEntityType());
		final FilterIndex sourceFilterIndex = target.getSourceFilterIndex(
			sourceEntityType, resolution.sourceAttributeName(), locale
		);
		if (sourceFilterIndex == null) {
			if (log.isDebugEnabled()) {
				log.debug(
					"Source FilterIndex for entity `{}` attr `{}` locale `{}` not found -- skipping histogram.",
					sourceEntityType, resolution.sourceAttributeName(), locale
				);
			}
			return;
		}

		final Class<? extends Serializable> plainType = resolution.plainType();
		final boolean localized = resolution.localized();
		final RoaringBitmap shouldBeIndexedBitmap =
			RoaringBitmapBackedBitmap.getRoaringBitmap(histogramShouldBeIndexed);
		final ValueToRecordBitmap[] sourceBuckets =
			sourceFilterIndex.getHistogramOfAllRecords().getHistogramBuckets();
		// Track which referenced entity PKs were matched in at least one bucket so that defaults can be applied.
		final RoaringBitmap encounteredRefPKs = new RoaringBitmap();

		for (final ValueToRecordBitmap sourceBucket : sourceBuckets) {
			final Serializable bucketValue = sourceBucket.getValue();
			// Only numeric attribute values are valid histogram bucket values; skip non-numeric types.
			if (!(bucketValue instanceof Number numericValue)) {
				continue;
			}
			final RoaringBitmap refPKsInBucket =
				RoaringBitmapBackedBitmap.getRoaringBitmap(sourceBucket.getRecordIds());

			for (final AffectedReferenceGroup group : affected.groups()) {
				// Check whether this group's referenced entity is present in the current source bucket.
				if (!refPKsInBucket.contains(group.referencedEntityPK())) {
					continue;
				}
				encounteredRefPKs.add(group.referencedEntityPK());
				final RoaringBitmap ownerPKs =
					RoaringBitmapBackedBitmap.getRoaringBitmap(group.ownerPKs());
				// Intersect "should be indexed" with the group's owner PKs to avoid touching unrelated entities.
				final RoaringBitmap matched = RoaringBitmap.and(shouldBeIndexedBitmap, ownerPKs);
				for (int ownerPK : matched) {
					insertHistogramValue(
						histogramName, localized, locale, numericValue, ownerPK, group, rtei, isGrouped,
						target, plainType
					);
				}
			}
		}

		// default values for missing referenced entities (already typed to plainType at build time)
		final Number defaultValue = resolution.defaultValue();
		if (defaultValue != null) {
			for (final AffectedReferenceGroup group : affected.groups()) {
				// Skip groups whose referenced entity was already found in at least one source bucket.
				if (encounteredRefPKs.contains(group.referencedEntityPK())) {
					continue;
				}
				final RoaringBitmap ownerPKs =
					RoaringBitmapBackedBitmap.getRoaringBitmap(group.ownerPKs());
				final RoaringBitmap matched = RoaringBitmap.and(shouldBeIndexedBitmap, ownerPKs);
				for (int ownerPK : matched) {
					insertHistogramValue(
						histogramName, localized, locale, defaultValue, ownerPK, group, rtei, isGrouped,
						target, plainType
					);
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
		@Nonnull String histogramName, @Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed, @Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei, boolean isGrouped,
		@Nonnull IndexMutationTarget target, @Nonnull String referenceName,
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
		@Nonnull String histogramName, @Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull Bitmap histogramShouldBeIndexed, @Nonnull AffectedEntityResolution affected,
		@Nonnull ReferencedTypeEntityIndex rtei, boolean isGrouped,
		@Nonnull IndexMutationTarget target, @Nonnull String referenceName
	) {
		final RoaringBitmap shouldBeIndexedBitmap =
			RoaringBitmapBackedBitmap.getRoaringBitmap(histogramShouldBeIndexed);
		final String sourceAttrName = resolution.sourceAttributeName();
		final AttributeIndexKey attrKey = new AttributeIndexKey(referenceName, sourceAttrName, locale);

		if (isGrouped) {
			// For grouped references, the reference attribute FilterIndex is partitioned per group:
			// each ReducedGroupEntityIndex holds its own per-reference attribute index.
			for (final AffectedReferenceGroup group : affected.groups()) {
				if (group.groupPK() == null) {
					continue;
				}
				final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
				for (int storagePK : storagePKs) {
					final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
						target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
					);
					processRefAttrFilterIndex(
						histogramName, locale, resolution, rgei.getFilterIndex(attrKey),
						shouldBeIndexedBitmap, group, rtei, isGrouped, target
					);
				}
			}
		} else {
			// For ungrouped references, a single FilterIndex on the RTEI covers all owner entities.
			final FilterIndex refAttrFilterIndex = rtei.getFilterIndex(attrKey);
			for (final AffectedReferenceGroup group : affected.groups()) {
				processRefAttrFilterIndex(
					histogramName, locale, resolution, refAttrFilterIndex,
					shouldBeIndexedBitmap, group, rtei, isGrouped, target
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
		@Nonnull String histogramName, @Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nullable FilterIndex filterIndex, @Nonnull RoaringBitmap shouldBeIndexedBitmap,
		@Nonnull AffectedReferenceGroup group, @Nonnull ReferencedTypeEntityIndex rtei,
		boolean isGrouped, @Nonnull IndexMutationTarget target
	) {
		final Class<? extends Serializable> plainType = resolution.plainType();
		final boolean localized = resolution.localized();
		final RoaringBitmap ownerPKs = RoaringBitmapBackedBitmap.getRoaringBitmap(group.ownerPKs());
		if (filterIndex == null) {
			// apply defaults if available (already typed to plainType at build time)
			final Number defaultValue = resolution.defaultValue();
			if (defaultValue != null) {
				final RoaringBitmap matched = RoaringBitmap.and(shouldBeIndexedBitmap, ownerPKs);
				for (int ownerPK : matched) {
					insertHistogramValue(
						histogramName, localized, locale, defaultValue, ownerPK, group, rtei, isGrouped,
						target, plainType
					);
				}
			}
			return;
		}

		final ValueToRecordBitmap[] buckets =
			filterIndex.getHistogramOfAllRecords().getHistogramBuckets();
		// Track owner PKs that were matched in at least one bucket so defaults can be applied to the rest.
		final RoaringBitmap encountered = new RoaringBitmap();

		for (final ValueToRecordBitmap bucket : buckets) {
			final Serializable bucketValue = bucket.getValue();
			// Non-numeric values are not valid histogram bucket values; skip silently.
			if (!(bucketValue instanceof Number numericValue)) {
				continue;
			}
			final RoaringBitmap bucketPKs =
				RoaringBitmapBackedBitmap.getRoaringBitmap(bucket.getRecordIds());
			// Triple intersection: eligible PKs ∩ group's owner PKs ∩ PKs that carry this attribute value.
			final RoaringBitmap matched = RoaringBitmap.and(
				RoaringBitmap.and(shouldBeIndexedBitmap, ownerPKs), bucketPKs
			);
			if (matched.isEmpty()) {
				continue;
			}
			encountered.or(matched);
			for (int ownerPK : matched) {
				insertHistogramValue(
					histogramName, localized, locale, numericValue, ownerPK, group, rtei, isGrouped,
					target, plainType
				);
			}
		}

		// defaults for missing (already typed to plainType at build time)
		final Number defaultValue = resolution.defaultValue();
		if (defaultValue != null) {
			// Eligible PKs that were never matched in any bucket lack the attribute value.
			final RoaringBitmap eligible = RoaringBitmap.and(shouldBeIndexedBitmap, ownerPKs);
			final RoaringBitmap missing = RoaringBitmap.andNot(eligible, encountered);
			for (int ownerPK : missing) {
				insertHistogramValue(
					histogramName, localized, locale, defaultValue, ownerPK, group, rtei, isGrouped,
					target, plainType
				);
			}
		}
	}

	/**
	 * Inserts a histogram value into the RTEI and (for grouped references) into each matching
	 * {@link ReducedGroupEntityIndex}.
	 *
	 * @param histogramName  name of the histogram definition
	 * @param locale         locale for the histogram index, or `null` for non-localized
	 * @param value          the numeric value to insert
	 * @param ownerPK        primary key of the owner entity
	 * @param group          the group for reduced-index routing
	 * @param rtei           the top-level referenced-type index
	 * @param isGrouped      `true` when the reference has a group type
	 * @param target         access to entity collection indexes
	 * @param valueType      the plain numeric type of the attribute
	 */
	private static void insertHistogramValue(
		@Nonnull String histogramName, boolean localized, @Nullable Locale locale,
		@Nonnull Number value, int ownerPK, @Nonnull AffectedReferenceGroup group,
		@Nonnull ReferencedTypeEntityIndex rtei, boolean isGrouped,
		@Nonnull IndexMutationTarget target,
		@Nonnull Class<? extends Serializable> valueType
	) {
		if (isGrouped && group.groupPK() != null) {
			final int[] storagePKs = rtei.getAllReferenceIndexes(group.groupPK());
			for (int storagePK : storagePKs) {
				final ReducedGroupEntityIndex rgei = asReducedGroupEntityIndex(
					target.getOrCreateIndexByPrimaryKey(storagePK), storagePK
				);
				rgei.insertHistogramValue(histogramName, localized, locale, value, ownerPK, valueType);
			}
		}
		rtei.insertHistogramValue(histogramName, localized, locale, value, ownerPK, valueType);
	}

	// ---- AFFECTED ENTITY RESOLUTION ----

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
		if (storagePKs.length == 0) {
			return AffectedEntityResolution.EMPTY;
		}
		final List<AffectedReferenceGroup> groups = new ArrayList<>(storagePKs.length);
		for (int storagePK : storagePKs) {
			final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(storagePK);
			if (reducedIndex == null) {
				continue;
			}
			final Bitmap ownerPKs = reducedIndex.getAllPrimaryKeys();
			if (!ownerPKs.isEmpty()) {
				groups.add(new AffectedReferenceGroup(refEntityPK, null, ownerPKs));
			}
		}
		return new AffectedEntityResolution(groups);
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
		final RoaringBitmap childrenRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(childrenPKs);
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
							RoaringBitmap.and(
								childrenRoaring,
								RoaringBitmapBackedBitmap.getRoaringBitmap(ownerPKs)
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
		final RoaringBitmap childrenRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(childrenPKs);
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
						RoaringBitmap.and(
							childrenRoaring,
							RoaringBitmapBackedBitmap.getRoaringBitmap(ownerPKs)
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
		// Build the PK-scoping clause appropriate for the type of cross-entity dependency.
		final FilterConstraint pkScope = switch (dependencyType) {
			case GROUP_ENTITY_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE ->
				new GroupHaving(new EntityPrimaryKeyInSet(mutatedEntityPK));
			case REFERENCED_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_REFERENCE_ATTRIBUTE ->
				new EntityHaving(new EntityPrimaryKeyInSet(mutatedEntityPK));
			case PARENT_ENTITY_ATTRIBUTE, PARENT_ENTITY_REFERENCE_ATTRIBUTE ->
				throw new IllegalStateException("Unreachable");
		};
		// Walk the top-level children and inject the PK-scope into the matching referenceHaving clause.
		final FilterConstraint[] topChildren = triggerFilterBy.getChildren();
		final FilterConstraint[] newTopChildren = new FilterConstraint[topChildren.length];
		for (int i = 0; i < topChildren.length; i++) {
			if (topChildren[i] instanceof ReferenceHaving rh
				&& rh.getReferenceName().equals(referenceName)) {
				// Wrap existing referenceHaving children together with the new PK-scope inside an And.
				final FilterConstraint[] rhChildren = rh.getChildren();
				final FilterConstraint[] andChildren = new FilterConstraint[rhChildren.length + 1];
				System.arraycopy(rhChildren, 0, andChildren, 0, rhChildren.length);
				andChildren[rhChildren.length] = pkScope;
				newTopChildren[i] = new ReferenceHaving(referenceName, new And(andChildren));
			} else {
				newTopChildren[i] = topChildren[i];
			}
		}
		return new FilterBy(newTopChildren);
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
		private final RoaringBitmap filterBitmap;
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
			this.filterBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(pks);
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
