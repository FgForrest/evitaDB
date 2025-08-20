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
import io.evitadb.core.Transaction;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.function.TriFunction;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetGroupIndex.FacetGroupIndexChanges;
import io.evitadb.index.facet.FacetReferenceIndex.FacetEntityTypeIndexChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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

	FacetReferenceIndex(@Nonnull String referenceName, @Nullable FacetGroupIndex noGroup, @Nonnull Map<Integer, FacetGroupIndex> groups, Map<Integer, int[]> facetToGroupIndex) {
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
		final FacetEntityTypeIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		final FacetGroupIndex facetGroupIndex;
		if (groupId == null) {
			final FacetGroupIndex existingNonGroupedFacetsIndex = this.notGroupedFacets.get();
			if (existingNonGroupedFacetsIndex == null) {
				facetGroupIndex = new FacetGroupIndex();
				this.notGroupedFacets.set(facetGroupIndex);
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(facetGroupIndex));
			} else {
				facetGroupIndex = existingNonGroupedFacetsIndex;
			}
		} else {
			this.facetToGroupIndex.merge(
				facetPrimaryKey,
				new int[]{groupId},
				(oldValues, newValues) -> ArrayUtils.insertIntIntoOrderedArray(newValues[0], oldValues)
			);
			// fetch or create index for referenced entity id (inside correct type)
			facetGroupIndex = this.groupedFacets.computeIfAbsent(groupId, gPK -> {
				final FacetGroupIndex fgIx = new FacetGroupIndex(gPK);
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(fgIx));
				return fgIx;
			});
		}

		return facetGroupIndex.addFacet(facetPrimaryKey, entityPrimaryKey);
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
			for (int group : groups) {
				final FacetGroupIndex examinedGroupIndex = this.groupedFacets.get(group);
				// there is no facet index present any more
				if (ofNullable(examinedGroupIndex).map(it -> it.getFacetIdIndex(facetPrimaryKey)).orElse(null) == null) {
					cleanedGroups = ArrayUtils.removeIntFromOrderedArray(groupId, cleanedGroups);
				}
			}
			if (ArrayUtils.isEmpty(cleanedGroups)) {
				this.facetToGroupIndex.remove(facetPrimaryKey);
			} else {
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
	public List<FacetGroupFormula> getFacetReferencingEntityIdsFormula(@Nonnull TriFunction<Integer, Bitmap, Bitmap[], FacetGroupFormula> formulaFactory, @Nonnull Bitmap facetId) {
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

	@Nonnull
	@Override
	public FacetReferenceIndex createCopyWithMergedTransactionalMemory(@Nullable FacetEntityTypeIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
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
	public static class FacetEntityTypeIndexChanges {
		private final TransactionalContainerChanges<FacetGroupIndexChanges, FacetGroupIndex, FacetGroupIndex> items = new TransactionalContainerChanges<>();

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
	}

	private record GroupFacetIdDTO(@Nullable FacetGroupIndex groupIndex, int facetId) {
	}

}
