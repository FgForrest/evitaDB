/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.facet;

import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges.ContainerChangesMemento;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.function.TriFunction;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetReferenceIndex.FacetEntityTypeIndexChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * FacetReferenceIndex contains information about all entity ids that use facet that is of this {@link #referenceName} as
 * their {@link Entity#getReference(String, int)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetReferenceIndex implements TransactionalLayerProducer<FacetEntityTypeIndexChanges, FacetReferenceIndex>, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains {@link ReferenceSchema#getName()} of the facets in this index.
	 */
	@Getter private final String referenceName;
	/**
	 * Represents index of facet to group relation - if none exists facet is either unknown or not assigned to any group.
	 * TOBEDONE JNO #501 - add consistency check that at the end of transaction, there is simple 1:1 relation in this sub index
	 */
	private final TransactionalMap<Integer, int[]> facetToGroupIndex;
	/**
	 * Represents index of {@link FacetGroupIndex}, the key is {@link FacetGroupIndex#getGroupId()}.
	 */
	private final TransactionalMap<Integer, FacetGroupIndex> groupedFacets;
	/**
	 * Represents index for all facets that are not organized in any group (has no relation to group).
	 */
	private final TransactionalReference<FacetGroupIndex> notGroupedFacets;

	public FacetReferenceIndex(@Nonnull String referenceName) {
		this.referenceName = referenceName;
		this.notGroupedFacets = new TransactionalReference<>(null);
		this.groupedFacets = new TransactionalMap<>(new HashMap<>(), FacetGroupIndex.class, Function.identity());
		this.facetToGroupIndex = new TransactionalMap<>(new HashMap<>());
	}

	public FacetReferenceIndex(@Nonnull String referenceName, @Nonnull Collection<FacetGroupIndex> groupIndexes) {
		FacetGroupIndex noGroup = null;
		final Map<Integer, FacetGroupIndex> internalMap = new HashMap<>();
		final Map<Integer, int[]> facetToGroup = new HashMap<>();
		for (FacetGroupIndex groupIndex : groupIndexes) {
			final Integer groupId = groupIndex.getGroupId();
			if (groupId == null) {
				Assert.isTrue(noGroup == null, "There is only single group without group id allowed!");
				noGroup = groupIndex;
			} else {
				internalMap.put(groupId, groupIndex);
				for (FacetIdIndex facetIdIndex : groupIndex.getFacetIdIndexes().values()) {
					facetToGroup.merge(
						facetIdIndex.getFacetId(),
						new int[]{groupId},
						(oldValues, newValues) -> ArrayUtils.insertIntIntoOrderedArray(newValues[0], oldValues)
					);
				}
			}
		}

		this.referenceName = referenceName;
		this.notGroupedFacets = new TransactionalReference<>(noGroup);
		this.groupedFacets = new TransactionalMap<>(internalMap, FacetGroupIndex.class, Function.identity());
		this.facetToGroupIndex = new TransactionalMap<>(facetToGroup);
	}

	FacetReferenceIndex(
		@Nonnull String referenceName,
		@Nullable FacetGroupIndex noGroup,
		@Nonnull Map<Integer, FacetGroupIndex> groups,
		@Nonnull Map<Integer, int[]> facetToGroupIndex
	) {
		this.referenceName = referenceName;
		this.notGroupedFacets = new TransactionalReference<>(noGroup);
		this.groupedFacets = new TransactionalMap<>(groups, FacetGroupIndex.class, Function.identity());
		this.facetToGroupIndex = new TransactionalMap<>(facetToGroupIndex);
	}

	/**
	 * Returns {@link FacetGroupIndex} of facets that are not present in any facet group.
	 */
	@Nullable
	public FacetGroupIndex getNotGroupedFacets() {
		return this.notGroupedFacets.get();
	}

	/**
	 * Returns collection of {@link FacetGroupIndex} that contain information about entity ids linked to facets of
	 * particular group.
	 */
	@Nonnull
	public Collection<FacetGroupIndex> getGroupedFacets() {
		return this.groupedFacets.values();
	}

	/**
	 * Adds new entity primary key to facet index of `facetPrimaryKey` and group identified by `groupId`.
	 *
	 * @return true if entity id was really added
	 */
	public boolean addFacet(int facetPrimaryKey, @Nullable Integer groupId, int entityPrimaryKey) {
		final FacetGroupIndex facetGroupIndex;
		// tracks whether the facet -> group mapping itself changed; it must contribute to the caller's dirty decision,
		// because that mapping lives in a diff layer of its own that only a dirty reference gets swept
		boolean groupMappingChanged = false;
		if (groupId == null) {
			final FacetGroupIndex existingNonGroupedFacetsIndex = this.notGroupedFacets.get();
			if (existingNonGroupedFacetsIndex == null) {
				final FacetEntityTypeIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
				facetGroupIndex = new FacetGroupIndex();
				this.notGroupedFacets.set(facetGroupIndex);
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(facetGroupIndex));
			} else {
				facetGroupIndex = existingNonGroupedFacetsIndex;
			}
		} else {
			// Record the facet -> group mapping only when it is not already there. `merge` would write unconditionally,
			// and a write acquires this map's transactional diff layer even when the resulting value is identical. That
			// layer is swept only if `FacetIndex` marks the reference dirty, which it does solely when the facet-to-entity
			// relation below is genuinely NEW - so re-adding an already indexed relation used to leave a diff layer that
			// nothing accounts for, and the commit then aborted with StaleTransactionMemoryException, suspending
			// the catalog. Not writing at all is both the fix and the cheaper path
			final int[] existingGroups = this.facetToGroupIndex.get(facetPrimaryKey);
			if (existingGroups == null) {
				this.facetToGroupIndex.put(facetPrimaryKey, new int[]{groupId});
				groupMappingChanged = true;
			} else if (!ArrayUtils.contains(existingGroups, groupId)) {
				this.facetToGroupIndex.put(
					facetPrimaryKey, ArrayUtils.insertIntIntoOrderedArray(groupId, existingGroups)
				);
				groupMappingChanged = true;
			}
			// fetch or create index for referenced entity id (inside correct type)
			final FacetGroupIndex existingGroupedIndex = this.groupedFacets.get(groupId);
			if (existingGroupedIndex == null) {
				final FacetEntityTypeIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
				final FacetGroupIndex fgIx = new FacetGroupIndex(groupId);
				this.groupedFacets.put(groupId, fgIx);
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(fgIx));
				facetGroupIndex = fgIx;
			} else {
				facetGroupIndex = existingGroupedIndex;
			}
		}

		// the group mapping must be reported as a change of its own - a transaction that only moved a facet between
		// groups leaves the facet-to-entity relation untouched, and reporting `false` there would leave this index's
		// diff layer unswept exactly as the unconditional write above used to
		final boolean added = facetGroupIndex.addFacet(facetPrimaryKey, entityPrimaryKey);
		return added || groupMappingChanged;
	}

	/**
	 * Removes entity primary key from index of `facetPrimaryKey` facet and group identified by `groupId`.
	 *
	 * @return true if entity id was really removed
	 */
	public boolean removeFacet(int facetPrimaryKey, @Nullable Integer groupId, int entityPrimaryKey) {
		final FacetGroupIndex facetGroupIndex;
		if (groupId == null) {
			facetGroupIndex = this.notGroupedFacets.get();
		} else {
			// fetch or create index for referenced entity id (inside correct type)
			facetGroupIndex = this.groupedFacets.get(groupId);
		}
		// fetch index for referenced entity type
		Assert.notNull(facetGroupIndex, "Facet `" + facetPrimaryKey + "` not found in index (group: `" + groupId + "`)!");
		boolean removed = facetGroupIndex.removeFacet(facetPrimaryKey, entityPrimaryKey);

		// remove facet to group mapping
		if (groupId != null) {
			final int[] groups = this.facetToGroupIndex.get(facetPrimaryKey);
			int[] cleanedGroups = groups;
			if (groups != null) {
				for (int group : groups) {
					final FacetGroupIndex examinedGroupIndex = this.groupedFacets.get(group);
					// there is no facet index present any more - drop THIS group from the mapping. It must be `group`
					// and not `groupId`: the loop exists to re-check every group the facet claims to be in, so the group
					// it finds stale is not necessarily the one this call removed from. Today the two always coincide,
					// because the mapping is maintained as an exact mirror of the group indexes and only `groupId`'s
					// membership can have changed by the time we get here - but that makes the old code correct only by
					// the invariant it is itself supposed to restore, and it would silently drop a LIVE group and keep
					// a stale one the moment the mapping ever diverged
					if (ofNullable(examinedGroupIndex).map(it -> it.getFacetIdIndex(facetPrimaryKey)).orElse(
						null) == null) {
						cleanedGroups = ArrayUtils.removeIntFromOrderedArray(group, cleanedGroups);
					}
				}
			}
			if (ArrayUtils.isEmpty(cleanedGroups)) {
				// only touch the map when the facet actually HAD a group mapping - removing an absent key would acquire
				// a diff layer for a pure no-op, and a layer nothing else in this transaction accounts for is orphaned
				// at commit (see the identical reasoning in `addFacet`)
				if (groups != null) {
					this.facetToGroupIndex.remove(facetPrimaryKey);
				}
			} else if (cleanedGroups != groups) {
				this.facetToGroupIndex.put(facetPrimaryKey, cleanedGroups);
			}
		}

		// if facet was removed check whether there are any data left
		if (removed && facetGroupIndex.isEmpty()) {
			// we need to keep track of removed internal transactional memory related data structures
			final FacetEntityTypeIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			// remove the index entirely
			if (groupId == null) {
				this.notGroupedFacets.set(null);
			} else {
				this.groupedFacets.remove(groupId);
			}
			facetGroupIndex.removeLayer();
			ofNullable(txLayer).ifPresent(it -> it.addRemovedItem(facetGroupIndex));
		}
		return removed;
	}

	/**
	 * Returns true if there is no entity id linked to any facet of this `entityType` and the entire index is useless.
	 */
	public boolean isEmpty() {
		if (!ofNullable(this.notGroupedFacets.get()).map(FacetGroupIndex::isEmpty).orElse(true)) {
			return false;
		}
		return this.groupedFacets
			.values()
			.stream()
			.allMatch(FacetGroupIndex::isEmpty);
	}

	/**
	 * Returns count of all entity ids referring to all facets of this `entityType`.
	 */
	public int size() {
		return ofNullable(this.notGroupedFacets.get()).map(FacetGroupIndex::size).orElse(0) +
			this.groupedFacets.values().stream().mapToInt(FacetGroupIndex::size).sum();
	}

	/**
	 * Returns the heap this reference's facet indexes occupy, in bytes — this object, the facet-to-group mapping with
	 * its group arrays, every grouped {@link FacetGroupIndex} and the non-grouped one.
	 *
	 * {@link #referenceName} contributes its **slot alone**: it is the schema's reference name, the very instance the
	 * enclosing {@link FacetIndex} files this index under. The boxed keys of both maps are charged, and are charged
	 * separately even where they hold the same facet id — {@link #facetToGroupIndex} and the per-group facet map box
	 * it at their own registration sites, so they are two objects, and rule 1 charges a box to every holder rather
	 * than letting `-XX:AutoBoxCacheMax` decide the answer.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// id, then the referenceName / facetToGroupIndex / groupedFacets / notGroupedFacets slots
		return layout.sizeOfObject(Long.BYTES + 4L * layout.referenceSize())
			+ this.facetToGroupIndex.getHeapSizeInBytes(
				key -> boxedInteger, groups -> layout.sizeOfArray(groups.length, Integer.BYTES)
			)
			+ this.groupedFacets.getHeapSizeInBytes(key -> boxedInteger, FacetGroupIndex::getHeapSizeInBytes)
			+ this.notGroupedFacets.getHeapSizeInBytes(FacetGroupIndex::getHeapSizeInBytes);
	}

	/**
	 * Returns stream of all {@link FacetGroupIndex} in this index. It combines both non-grouped and grouped indexes.
	 */
	@Nonnull
	public Stream<FacetGroupIndex> getFacetGroupIndexesAsStream() {
		final Stream<FacetGroupIndex> groupStream = this.groupedFacets
			.values()
			.stream();
		return this.notGroupedFacets.get() == null ?
			groupStream :
			Stream.concat(
				Stream.of(this.notGroupedFacets.get()),
				groupStream
			);
	}

	/**
	 * Returns {@link FacetGroupIndex} for passed group id.
	 */
	@Nullable
	public FacetGroupIndex getFacetsInGroup(@Nullable Integer groupId) {
		return groupId == null ? this.notGroupedFacets.get() : this.groupedFacets.get(groupId);
	}

	/**
	 * Method returns formula that allows computation of all entity primary keys that have at least one
	 * of `facetId` as its faceted reference.
	 */
	@Nonnull
	public List<FacetGroupFormula> getFacetReferencingEntityIdsFormula(
		@Nonnull TriFunction<Integer, Bitmap, Bitmap[], FacetGroupFormula> formulaFactory,
		@Nonnull Bitmap facetId
	) {
		final Map<FacetGroupIndex, List<Integer>> facetsByGroup = StreamSupport.stream(facetId.spliterator(), false)
			.flatMap(fId -> ofNullable(this.facetToGroupIndex.get(fId))
				.map(groupIds -> Arrays.stream(groupIds).mapToObj(groupId -> new GroupFacetIdDTO(this.groupedFacets.get(groupId), fId)))
				.orElseGet(() -> Stream.of(new GroupFacetIdDTO(this.notGroupedFacets.get(), fId)))
			)
			.filter(it -> it.groupIndex() != null)
			.collect(
				Collectors.groupingBy(
					GroupFacetIdDTO::groupIndex,
					Collectors.mapping(GroupFacetIdDTO::facetId, Collectors.toList())
				)
			);
		return facetsByGroup
			.entrySet()
			.stream()
			.map(entry -> {
				final FacetGroupIndex groupIndex = entry.getKey();
				if (groupIndex == null) {
					return null;
				} else {
					final BaseBitmap groupFacets = new BaseBitmap(entry.getValue().stream().mapToInt(it -> it).toArray());
					//noinspection DataFlowIssue
					return formulaFactory.apply(
						groupIndex.getGroupId(), groupFacets, groupIndex.getFacetIdIndexesAsArray(groupFacets)
					);
				}
			})
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	/**
	 * Method returns true if facet id is part of the passed group id for specified `entityType`.
	 */
	public boolean isFacetInGroup(int groupId, int facetId) {
		return ofNullable(this.facetToGroupIndex.get(facetId))
			.map(it -> Arrays.binarySearch(it, groupId) >= 0)
			.orElse(false);
	}

	/**
	 * Returns the group ID for the given facet primary key, or `null` if the facet is ungrouped or not found in any
	 * group. Used by the cross-entity re-evaluation executor ReevaluateExpressionExecutor to determine the group
	 * assignment when resolving {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} dependencies.
	 *
	 * @param facetPK the primary key of the facet (referenced entity)
	 * @return the group ID, or `null` for ungrouped facets or if the facet is not found
	 */
	@Nullable
	public Integer getGroupIdForFacet(int facetPK) {
		final int[] groupIds = this.facetToGroupIndex.get(facetPK);
		if (groupIds != null && groupIds.length > 0) {
			return groupIds[0];
		}
		// ungrouped facets and unknown facets both resolve to null
		return null;
	}

	/**
	 * Returns contents of non-grouped facet index as plain non-transactional map.
	 */
	@Nonnull
	public Optional<Map<Integer, Bitmap>> getNotGroupedFacetsAsMap() {
		return ofNullable(this.notGroupedFacets.get())
			.map(FacetGroupIndex::getAsMap);
	}

	/**
	 * Returns contents of grouped facet indexes as plain non-transactional map.
	 */
	@Nonnull
	public Map<Integer, Map<Integer, Bitmap>> getGroupsAsMap() {
		final Map<Integer, Map<Integer, Bitmap>> result = createHashMap(this.groupedFacets.size());
		for (Entry<Integer, FacetGroupIndex> entry : this.groupedFacets.entrySet()) {
			result.put(entry.getKey(), entry.getValue().getAsMap());
		}
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(128);
		if (this.notGroupedFacets.get() != null) {
			sb.append("\t").append(this.notGroupedFacets.get());
		}
		if (this.notGroupedFacets.get() != null && !this.groupedFacets.isEmpty()) {
			sb.append("\n");
		}
		this.groupedFacets
			.keySet()
			.stream()
			.sorted()
			.forEach(group -> sb.append("\t").append(this.groupedFacets.get(group)));
		return sb.toString();
	}

	@Override
	public void resetDirty() {
		// do nothing here
	}

	/*
		Implementation of TransactionalLayerProducer
	 */

	@Override
	public FacetEntityTypeIndexChanges createLayer() {
		return new FacetEntityTypeIndexChanges();
	}

	/**
	 * {@link FacetEntityTypeIndexChanges} is pure in-transaction bookkeeping — it records which contained group
	 * indexes a commit-merge has to visit — and the delegate branch is an explicit `if (txLayer != null)` that writes
	 * nothing. The facet data a mutation touches lives in the contained {@link FacetGroupIndex} instances and the
	 * transactional maps holding them, which journal their own warm-up writes.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	@Nonnull
	@Override
	public FacetReferenceIndex createCopyWithMergedTransactionalMemory(
		@Nullable FacetEntityTypeIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final FacetGroupIndex noGroupCopy = transactionalLayer.getStateCopyWithCommittedChanges(this.notGroupedFacets)
			.map(transactionalLayer::getStateCopyWithCommittedChanges)
			.orElse(null);
		final Map<Integer, FacetGroupIndex> groupCopy = transactionalLayer.getStateCopyWithCommittedChanges(this.groupedFacets);
		final Map<Integer, int[]> facetToGroupCopy = transactionalLayer.getStateCopyWithCommittedChanges(this.facetToGroupIndex);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return new FacetReferenceIndex(this.referenceName, noGroupCopy, groupCopy, facetToGroupCopy);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		ofNullable(this.notGroupedFacets).ifPresent(it -> it.removeLayer(transactionalLayer));
		this.groupedFacets.removeLayer(transactionalLayer);
		this.facetToGroupIndex.removeLayer(transactionalLayer);
		final FacetEntityTypeIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #groupedFacets} transactional map and its sub structure.
	 */
	public static class FacetEntityTypeIndexChanges implements Snapshotable<FacetEntityTypeIndexChanges.FacetEntityTypeIndexChangesMemento> {
		private final TransactionalContainerChanges<FacetGroupIndex, FacetGroupIndex> items = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull FacetGroupIndex baseIndex) {
			this.items.addCreatedItem(baseIndex);
		}

		public void addRemovedItem(@Nonnull FacetGroupIndex baseIndex) {
			this.items.addRemovedItem(baseIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.items.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.items.cleanAll(transactionalLayer);
		}

		@Nonnull
		@Override
		public FacetEntityTypeIndexChangesMemento snapshot() {
			return new FacetEntityTypeIndexChangesMemento(this.items.snapshot());
		}

		@Override
		public void restore(@Nonnull FacetEntityTypeIndexChangesMemento memento) {
			this.items.restore(memento.items());
		}

		/**
		 * Memento bundling the savepoint state of every {@link TransactionalContainerChanges} this aggregate tracks.
		 *
		 * @param items snapshot of the facet-group-index created/removed bookkeeping
		 */
		public record FacetEntityTypeIndexChangesMemento(
			@Nonnull ContainerChangesMemento<FacetGroupIndex> items
		) {
		}
	}

	private record GroupFacetIdDTO(@Nullable FacetGroupIndex groupIndex, int facetId) {
	}

}
