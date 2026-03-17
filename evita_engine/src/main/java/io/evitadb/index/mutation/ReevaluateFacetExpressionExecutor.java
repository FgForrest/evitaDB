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
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Re-evaluates the `facetedPartially` expression for all owner entities affected by a cross-entity change. When a
 * group entity or referenced entity attribute changes, this executor:
 *
 * 1. Resolves affected owner entity PKs and associated facet PKs from the target collection's own indexes
 *    (two-step lookup via {@link ReferencedTypeEntityIndex} -> {@link ReducedGroupEntityIndex} /
 *    {@link io.evitadb.index.ReducedEntityIndex ReducedEntityIndex})
 * 2. Gets the pre-translated {@link FilterBy} from the trigger, parameterizes it with the mutated entity PK
 *    to scope the query to the specific changed entity
 * 3. Evaluates the parameterized filter against current indexes to determine which entities currently satisfy
 *    the expression
 * 4. Compares the query result with the affected set using bitmap operations and performs the necessary
 *    add/remove facet operations
 *
 * This executor is a **stateless singleton** — all collection-specific state is received via
 * {@link IndexMutationTarget}. Registered in {@link IndexMutationExecutorRegistry}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class ReevaluateFacetExpressionExecutor
	implements IndexMutationExecutor<ReevaluateFacetExpressionMutation> {

	/**
	 * Structured result of PK resolution. Maps each resolved index to a `(facetPK, groupPK, ownerPKs)` tuple —
	 * all information needed to construct {@link ReferenceKey} and call `addFacet`/`removeFacet`.
	 *
	 * @param groups list of resolved facet groups, each carrying a facetPK, groupPK, and owner PK bitmap
	 */
	record AffectedEntityResolution(@Nonnull List<AffectedFacetGroup> groups) {

		/** Empty resolution constant — no affected entities. */
		static final AffectedEntityResolution EMPTY = new AffectedEntityResolution(List.of());

		/**
		 * Returns the union of all owner PKs across all groups. Computed eagerly on each call — since this
		 * method is called once (for the bitmap set operations), caching is not needed.
		 *
		 * @return union bitmap of all owner entity primary keys
		 */
		@Nonnull
		Bitmap allOwnerPKs() {
			if (this.groups.isEmpty()) {
				return new BaseBitmap();
			}
			if (this.groups.size() == 1) {
				return this.groups.get(0).ownerPKs();
			}
			RoaringBitmap union = RoaringBitmapBackedBitmap.getRoaringBitmapClone(this.groups.get(0).ownerPKs());
			for (int i = 1; i < this.groups.size(); i++) {
				union.or(RoaringBitmapBackedBitmap.getRoaringBitmap(this.groups.get(i).ownerPKs()));
			}
			return new BaseBitmap(union);
		}

		/**
		 * Returns a lazy iterable of {@link AffectedFacetEntry} instances filtered to only those whose
		 * `ownerPK` is present in the given bitmap. Avoids materializing the full list — iterates groups
		 * and checks bitmap membership on the fly.
		 *
		 * @param pks bitmap of owner entity PKs to include
		 * @return lazy iterable of matching entries
		 */
		@Nonnull
		Iterable<AffectedFacetEntry> entriesForOwnerPKs(@Nonnull Bitmap pks) {
			return () -> new FilteredEntryIterator(this.groups, pks);
		}
	}

	/**
	 * Represents a single `(facetPK, groupPK)` tuple with the set of owner entity PKs that have this facet in
	 * this group. The `groupPK` is nullable to support ungrouped facets.
	 *
	 * @param facetPK  the primary key of the facet (referenced entity)
	 * @param groupPK  the primary key of the group entity, or `null` for ungrouped facets
	 * @param ownerPKs bitmap of owner entity primary keys referencing this facet in this group
	 */
	record AffectedFacetGroup(int facetPK, @Nullable Integer groupPK, @Nonnull Bitmap ownerPKs) {
	}

	/**
	 * Individual entry for iteration during add/remove operations — one entry per `(facetPK, groupPK, ownerPK)`
	 * combination. The `groupPK` is nullable to support ungrouped facets.
	 *
	 * @param facetPK the primary key of the facet (referenced entity)
	 * @param groupPK the primary key of the group entity, or `null` for ungrouped facets
	 * @param ownerPK the primary key of the owner entity
	 */
	record AffectedFacetEntry(int facetPK, @Nullable Integer groupPK, int ownerPK) {
	}

	@Override
	public void execute(
		@Nonnull ReevaluateFacetExpressionMutation mutation,
		@Nonnull IndexMutationTarget target
	) {
		// 1. Resolve affected (facetPK, groupPK, ownerPKs) tuples
		final AffectedEntityResolution affected = resolveAffected(target, mutation);
		final Bitmap allAffectedOwnerPKs = affected.allOwnerPKs();
		if (allAffectedOwnerPKs.isEmpty()) {
			return; // no affected entities — nothing to do
		}

		// 2. Get the pre-translated FilterBy from the trigger
		final ExpressionIndexTrigger trigger = target.getTrigger(
			mutation.referenceName(),
			mutation.dependencyType(),
			mutation.scope()
		);
		if (trigger == null) {
			return; // no expression — nothing to reevaluate
		}

		// 3. Parameterize the FilterBy with the mutated entity PK
		final FilterBy parameterizedFilter = parameterize(
			trigger.getFilterByConstraint(),
			mutation.referenceName(),
			mutation.mutatedEntityPK(),
			mutation.dependencyType()
		);

		// 4. Evaluate against current indexes — PKs where expression is TRUE now
		final Bitmap currentlyTruePKs = target.evaluateFilter(parameterizedFilter, mutation.scope());

		// 5. Determine adds and removes via bitmap set operations
		final Bitmap shouldBeFaceted = RoaringBitmapBackedBitmap.and(
			new RoaringBitmap[]{
				RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
				RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
			}
		);
		final Bitmap shouldNotBeFaceted = new BaseBitmap(
			RoaringBitmap.andNot(
				RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
				RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
			)
		);

		// 6. Apply changes — resolve target indexes and perform facet operations
		final ReferenceSchemaContract refSchema = target.getEntitySchema()
			.getReference(mutation.referenceName())
			.orElseThrow();
		final String referenceName = mutation.referenceName();
		final Scope scope = mutation.scope();

		// always target GlobalEntityIndex
		final EntityIndex globalIndex = target.getIndexIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		Assert.notNull(
			globalIndex,
			"GlobalEntityIndex must exist for scope `" + scope + "`."
		);

		// determine if ReducedEntityIndex instances should also be targeted
		final boolean targetReduced =
			refSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
		final ReferencedTypeEntityIndex refTypeIndex = targetReduced
			? (ReferencedTypeEntityIndex) target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName))
			: null;

		// add facet entries for entities that should be faceted
		for (AffectedFacetEntry entry : affected.entriesForOwnerPKs(shouldBeFaceted)) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.facetPK());
			globalIndex.addFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			applyToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, true);
		}

		// remove facet entries for entities that should not be faceted
		for (AffectedFacetEntry entry : affected.entriesForOwnerPKs(shouldNotBeFaceted)) {
			final ReferenceKey refKey = new ReferenceKey(referenceName, entry.facetPK());
			globalIndex.removeFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
			applyToReducedIndexes(target, refTypeIndex, refSchema, refKey, entry, false);
		}
	}

	/**
	 * Resolves affected owner entity PKs and associated facet PKs using the target collection's indexes.
	 * The resolution path differs by {@link DependencyType}:
	 *
	 * - **{@link DependencyType#GROUP_ENTITY_ATTRIBUTE}** and
	 *   **{@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}**: looks up `ReferencedTypeEntityIndex`
	 *   for `REFERENCED_GROUP_ENTITY_TYPE`, then resolves per-facet owner PK bitmaps from each
	 *   {@link ReducedGroupEntityIndex}
	 * - **{@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE}** and
	 *   **{@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE}**: looks up
	 *   `ReferencedTypeEntityIndex` for `REFERENCED_ENTITY_TYPE`, then resolves owner PKs from each
	 *   `ReducedEntityIndex` and determines the group PK from `FacetReferenceIndex`
	 *
	 * @param target   limited view of the target collection
	 * @param mutation the mutation carrying reference name, mutated PK, dependency type, and scope
	 * @return structured resolution with per-facet groups and owner PK bitmaps
	 */
	@Nonnull
	private static AffectedEntityResolution resolveAffected(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateFacetExpressionMutation mutation
	) {
		return switch (mutation.dependencyType()) {
			case GROUP_ENTITY_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE ->
				resolveForGroupEntityAttribute(target, mutation);
			case REFERENCED_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_REFERENCE_ATTRIBUTE ->
				resolveForReferencedEntityAttribute(target, mutation);
		};
	}

	/**
	 * Resolves affected entities for {@link DependencyType#GROUP_ENTITY_ATTRIBUTE} and
	 * {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}. The mutated entity is the group entity —
	 * facet PKs are recovered from each {@link ReducedGroupEntityIndex}'s `referencedPrimaryKeysIndex`,
	 * and groupPK = mutatedEntityPK.
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForGroupEntityAttribute(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateFacetExpressionMutation mutation
	) {
		final EntityIndex rteiIndex = target.getIndexIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, mutation.scope(), mutation.referenceName()
			)
		);
		if (rteiIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}

		final ReferencedTypeEntityIndex rtei = (ReferencedTypeEntityIndex) rteiIndex;
		final int[] storagePKs = rtei.getAllReferenceIndexes(mutation.mutatedEntityPK());
		if (storagePKs.length == 0) {
			return AffectedEntityResolution.EMPTY;
		}

		final int groupPK = mutation.mutatedEntityPK();
		final List<AffectedFacetGroup> groups = new ArrayList<>(storagePKs.length << 1);

		for (int storagePK : storagePKs) {
			final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(storagePK);
			if (reducedIndex == null) {
				continue;
			}
			if (reducedIndex instanceof ReducedGroupEntityIndex rgei) {
				// iterate per-facet owner PK bitmaps within this group index
				final Set<Integer> facetPKs = rgei.getReferencedEntityPrimaryKeys();
				for (int facetPK : facetPKs) {
					final Bitmap ownerPKs = rgei.getOwnerPKsForReferencedEntity(facetPK);
					if (ownerPKs != null && !ownerPKs.isEmpty()) {
						groups.add(new AffectedFacetGroup(facetPK, groupPK, ownerPKs));
					}
				}
			}
		}

		return new AffectedEntityResolution(groups);
	}

	/**
	 * Resolves affected entities for {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} and
	 * {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE}. The mutated entity is the referenced
	 * entity — facetPK = mutatedEntityPK, and groupPK is resolved from the `GlobalEntityIndex`'s
	 * {@link FacetReferenceIndex}.
	 */
	@Nonnull
	private static AffectedEntityResolution resolveForReferencedEntityAttribute(
		@Nonnull IndexMutationTarget target,
		@Nonnull ReevaluateFacetExpressionMutation mutation
	) {
		final EntityIndex rteiIndex = target.getIndexIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, mutation.scope(), mutation.referenceName()
			)
		);
		if (rteiIndex == null) {
			return AffectedEntityResolution.EMPTY;
		}

		final ReferencedTypeEntityIndex rtei = (ReferencedTypeEntityIndex) rteiIndex;
		final int[] storagePKs = rtei.getAllReferenceIndexes(mutation.mutatedEntityPK());
		if (storagePKs.length == 0) {
			return AffectedEntityResolution.EMPTY;
		}

		final int facetPK = mutation.mutatedEntityPK();

		// resolve group PK from GlobalEntityIndex's FacetIndex
		final Integer groupPK = resolveGroupPKForFacet(target, mutation.referenceName(), facetPK, mutation.scope());

		final List<AffectedFacetGroup> groups = new ArrayList<>(storagePKs.length);

		for (int storagePK : storagePKs) {
			final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(storagePK);
			if (reducedIndex == null) {
				continue;
			}
			final Bitmap ownerPKs = reducedIndex.getAllPrimaryKeys();
			if (!ownerPKs.isEmpty()) {
				groups.add(new AffectedFacetGroup(facetPK, groupPK, ownerPKs));
			}
		}

		return new AffectedEntityResolution(groups);
	}

	/**
	 * Resolves the group PK for a given facet PK by looking up the `GlobalEntityIndex`'s `FacetIndex` ->
	 * `FacetReferenceIndex`. Returns `null` for ungrouped facets or if the facet is not currently indexed.
	 *
	 * @param target        limited view of the target collection
	 * @param referenceName name of the reference
	 * @param facetPK       primary key of the facet (referenced entity)
	 * @param scope         scope of the expression
	 * @return group PK, or `null` for ungrouped/not-found facets
	 */
	@Nullable
	private static Integer resolveGroupPKForFacet(
		@Nonnull IndexMutationTarget target,
		@Nonnull String referenceName,
		int facetPK,
		@Nonnull Scope scope
	) {
		final EntityIndex globalIndex = target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex == null) {
			return null;
		}
		final Map<String, FacetReferenceIndex> facetingEntities = globalIndex.getFacetingEntities();
		final FacetReferenceIndex facetRefIndex = facetingEntities.get(referenceName);
		if (facetRefIndex == null) {
			return null;
		}
		return facetRefIndex.getGroupIdForFacet(facetPK);
	}

	/**
	 * Parameterizes the pre-translated {@link FilterBy} constraint with a PK-scoping clause for the mutated
	 * entity. This prevents false positives from unrelated groups/references when the query is evaluated
	 * against the collection's indexes.
	 *
	 * - **Group dependency types** ({@link DependencyType#GROUP_ENTITY_ATTRIBUTE},
	 *   {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}): inject
	 *   `groupHaving(entityPrimaryKeyInSet(mutatedPK))` within the matching `referenceHaving` clause
	 * - **Referenced entity dependency types** ({@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE},
	 *   {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE}): inject
	 *   `entityHaving(entityPrimaryKeyInSet(mutatedPK))` within the matching `referenceHaving` clause
	 *
	 * @param triggerFilterBy the pre-translated FilterBy from the trigger
	 * @param referenceName   reference name to match the `referenceHaving` node
	 * @param mutatedEntityPK the PK of the mutated entity (group or referenced)
	 * @param dependencyType  how the mutated entity relates to the owner entity
	 * @return parameterized FilterBy with PK-scoping constraint injected
	 */
	@Nonnull
	private static FilterBy parameterize(
		@Nonnull FilterBy triggerFilterBy,
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType
	) {
		final FilterConstraint pkScope = switch (dependencyType) {
			case GROUP_ENTITY_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE ->
				new GroupHaving(new EntityPrimaryKeyInSet(mutatedEntityPK));
			case REFERENCED_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_REFERENCE_ATTRIBUTE ->
				new EntityHaving(new EntityPrimaryKeyInSet(mutatedEntityPK));
		};

		// walk FilterBy top-level children to find the matching ReferenceHaving node
		final FilterConstraint[] topChildren = triggerFilterBy.getChildren();
		final FilterConstraint[] newTopChildren = new FilterConstraint[topChildren.length];

		for (int i = 0; i < topChildren.length; i++) {
			if (topChildren[i] instanceof ReferenceHaving rh
				&& rh.getReferenceName().equals(referenceName)) {
				// inject pkScope as AND sibling of existing children
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
	 * Applies a facet add or remove operation to applicable {@link io.evitadb.index.ReducedEntityIndex} instances
	 * for the given facet entry. Only called when the reference schema has
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} level. Looks up reduced indexes via the
	 * {@link ReferencedTypeEntityIndex} for the facet PK.
	 *
	 * @param target       limited view of the target collection
	 * @param refTypeIndex the `ReferencedTypeEntityIndex` for `REFERENCED_ENTITY_TYPE`, or `null` if reduced
	 *                     targeting is disabled
	 * @param refSchema    the reference schema
	 * @param refKey       the reference key `(referenceName, facetPK)`
	 * @param entry        the affected facet entry
	 * @param add          `true` for add, `false` for remove
	 */
	private static void applyToReducedIndexes(
		@Nonnull IndexMutationTarget target,
		@Nullable ReferencedTypeEntityIndex refTypeIndex,
		@Nonnull ReferenceSchemaContract refSchema,
		@Nonnull ReferenceKey refKey,
		@Nonnull AffectedFacetEntry entry,
		boolean add
	) {
		if (refTypeIndex == null) {
			return;
		}
		final int[] reducedStoragePKs = refTypeIndex.getAllReferenceIndexes(entry.facetPK());
		for (int reducedStoragePK : reducedStoragePKs) {
			final EntityIndex reducedIndex = target.getIndexByPrimaryKeyIfExists(reducedStoragePK);
			if (reducedIndex != null) {
				if (add) {
					reducedIndex.addFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
				} else {
					reducedIndex.removeFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
				}
			}
		}
	}

	/**
	 * Lazy iterator that traverses all {@link AffectedFacetGroup} entries and yields only those
	 * {@link AffectedFacetEntry} instances whose `ownerPK` is present in the given filter bitmap.
	 * Avoids materializing the full entry list — checks bitmap membership on the fly.
	 */
	private static class FilteredEntryIterator implements Iterator<AffectedFacetEntry> {
		private final List<AffectedFacetGroup> groups;
		private final RoaringBitmap filterBitmap;
		private int groupIdx;
		@Nullable private int[] currentOwnerPKs;
		private int ownerIdx;
		private int currentFacetPK;
		@Nullable private Integer currentGroupPK;
		@Nullable private AffectedFacetEntry nextEntry;

		/**
		 * Creates a new filtered entry iterator.
		 *
		 * @param groups all resolved facet groups
		 * @param pks    bitmap of owner PKs to include
		 */
		FilteredEntryIterator(@Nonnull List<AffectedFacetGroup> groups, @Nonnull Bitmap pks) {
			this.groups = groups;
			this.filterBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(pks);
			this.groupIdx = 0;
			this.ownerIdx = 0;
			this.currentOwnerPKs = null;
			advance();
		}

		@Override
		public boolean hasNext() {
			return this.nextEntry != null;
		}

		@Override
		public AffectedFacetEntry next() {
			if (this.nextEntry == null) {
				throw new NoSuchElementException();
			}
			final AffectedFacetEntry result = this.nextEntry;
			advance();
			return result;
		}

		/**
		 * Advances to the next matching entry, setting `nextEntry` to the result or `null` if exhausted.
		 */
		private void advance() {
			this.nextEntry = null;
			while (this.groupIdx < this.groups.size()) {
				if (this.currentOwnerPKs == null) {
					final AffectedFacetGroup group = this.groups.get(this.groupIdx);
					this.currentFacetPK = group.facetPK();
					this.currentGroupPK = group.groupPK();
					this.currentOwnerPKs = group.ownerPKs().getArray();
					this.ownerIdx = 0;
				}
				while (this.ownerIdx < this.currentOwnerPKs.length) {
					final int ownerPK = this.currentOwnerPKs[this.ownerIdx++];
					if (this.filterBitmap.contains(ownerPK)) {
						this.nextEntry = new AffectedFacetEntry(
							this.currentFacetPK, this.currentGroupPK, ownerPK
						);
						return;
					}
				}
				// exhausted current group — move to next
				this.currentOwnerPKs = null;
				this.groupIdx++;
			}
		}
	}
}
