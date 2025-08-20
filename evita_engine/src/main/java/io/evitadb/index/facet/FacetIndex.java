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

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Transaction;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.function.TriFunction;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetIndex.FacetIndexChanges;
import io.evitadb.index.facet.FacetReferenceIndex.FacetEntityTypeIndexChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.set.TransactionalSet;
import io.evitadb.store.spi.model.storageParts.index.FacetIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * Facet index provides fast O(1) access to the bitmaps of entity primary keys that refer to the faceted entity.
 * This index allows processing of {@link FacetHaving} filtering query and is used to
 * generate {@link io.evitadb.api.query.require.FacetSummary} response.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetIndex implements FacetIndexContract, TransactionalLayerProducer<FacetIndexChanges, FacetIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 7909305391436069776L;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Index that stores the key data needed for facet look-ups. Main key is {@link EntityReference#getType()}, secondary
	 * key is {@link EntityReference#getPrimaryKey()}. Values represent {@link Entity#getPrimaryKey()} that posses of
	 * this facet.
	 */
	private final TransactionalMap<String, FacetReferenceIndex> facetingEntities;
	/**
	 * This simple set structure contains set of {@link EntityReference#getType()} that contain any changes in its index.
	 * This is required because {@link FacetIndexStoragePart} stores only index for single {@link EntityReference#getType()}.
	 * All flags gets cleared on transaction commit - i.e. when {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
	 * is called.
	 */
	private final TransactionalSet<Serializable> dirtyIndexes;

	public FacetIndex() {
		this.facetingEntities = new TransactionalMap<>(new HashMap<>(), FacetReferenceIndex.class, Function.identity());
		this.dirtyIndexes = new TransactionalSet<>(new HashSet<>());
	}

	public FacetIndex(@Nonnull Collection<FacetIndexStoragePart> facetIndexStorageParts) {
		final Map<String, FacetReferenceIndex> baseIndex = createHashMap(facetIndexStorageParts.size());
		for (FacetIndexStoragePart facetIndexStoragePart : facetIndexStorageParts) {
			// we need to wrap non-transactional integer bitmap into a transactional one
			final Map<Integer, Map<Integer, Bitmap>> sourceGroupIndex = facetIndexStoragePart.getFacetingEntities();

			final List<FacetGroupIndex> indexes = new LinkedList<>();
			if (facetIndexStoragePart.getNoGroupFacetingEntities() != null) {
				final Map<Integer, Bitmap> sourceNoGroupFacetingEntities = facetIndexStoragePart.getNoGroupFacetingEntities();
				indexes.add(
					new FacetGroupIndex(
						sourceNoGroupFacetingEntities
							.entrySet()
							.stream()
							.map(it -> new FacetIdIndex(it.getKey(), it.getValue()))
							.collect(Collectors.toList())
					)
				);
			}
			for (Entry<Integer, Map<Integer, Bitmap>> groupEntry : sourceGroupIndex.entrySet()) {
				indexes.add(
					new FacetGroupIndex(
						groupEntry.getKey(),
						groupEntry.getValue()
							.entrySet()
							.stream()
							.map(it -> new FacetIdIndex(it.getKey(), it.getValue()))
							.collect(Collectors.toList())
					)
				);
			}
			final String referenceName = facetIndexStoragePart.getReferenceName();
			final FacetReferenceIndex facetEntityTypeIndex = new FacetReferenceIndex(referenceName, indexes);
			baseIndex.put(referenceName, facetEntityTypeIndex);
		}

		this.facetingEntities = new TransactionalMap<>(baseIndex, FacetReferenceIndex.class, Function.identity());
		this.dirtyIndexes = new TransactionalSet<>(new HashSet<>());
	}

	private FacetIndex(@Nonnull Map<String, FacetReferenceIndex> sourceFacetingEntities) {
		this.facetingEntities = new TransactionalMap<>(sourceFacetingEntities, FacetReferenceIndex.class, Function.identity());
		this.dirtyIndexes = new TransactionalSet<>(new HashSet<>());
	}

	@Override
	public void addFacet(@Nonnull ReferenceKey referenceKey, @Nullable Integer groupId, int entityPrimaryKey) {
		// we need to keep track of created internal transactional memory related data structures
		final FacetIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		// fetch or create index for referenced entity type
		final FacetReferenceIndex facetEntityTypeIndex = this.facetingEntities.computeIfAbsent(
			referenceKey.referenceName(),
			referencedEntityType -> {
				final FacetReferenceIndex fetIx = new FacetReferenceIndex(referenceKey.referenceName());
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(fetIx));
				return fetIx;
			});
		// now add the facet relation for entity primary key
		final boolean added = facetEntityTypeIndex.addFacet(referenceKey.primaryKey(), groupId, entityPrimaryKey);
		// if anything was changed mark the entity type index dirty
		if (added) {
			this.dirtyIndexes.add(referenceKey.referenceName());
		}
	}

	@Override
	public void removeFacet(@Nonnull ReferenceKey referenceKey, @Nullable Integer groupId, int entityPrimaryKey) {
		// fetch index for referenced entity type
		final FacetReferenceIndex facetEntityTypeIndex = this.facetingEntities.get(referenceKey.referenceName());
		Assert.notNull(facetEntityTypeIndex, "No facet found for reference `" + referenceKey.referenceName() + "`!");
		boolean removed = facetEntityTypeIndex.removeFacet(referenceKey.primaryKey(), groupId, entityPrimaryKey);
		// if facet was removed check whether there are any data left
		if (removed && facetEntityTypeIndex.isEmpty()) {
			// we need to keep track of removed internal transactional memory related data structures
			final FacetIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			// remove the index entirely
			this.facetingEntities.remove(referenceKey.referenceName());
			ofNullable(txLayer).ifPresent(it -> it.addRemovedItem(facetEntityTypeIndex));
		}
		// if anything was changed mark the entity type index dirty
		this.dirtyIndexes.add(referenceKey.referenceName());
	}

	@Nonnull
	@Override
	public Set<String> getReferencedEntities() {
		return this.facetingEntities.keySet();
	}

	@Override
	public List<FacetGroupFormula> getFacetReferencingEntityIdsFormula(@Nonnull String referenceName, @Nonnull TriFunction<Integer, Bitmap, Bitmap[], FacetGroupFormula> formulaFactory, @Nonnull Bitmap facetId) {
		// fetch index for referenced entity type
		final FacetReferenceIndex facetEntityTypeIndex = this.facetingEntities.get(referenceName);
		// if not found or empty, or input parameter is empty - return empty result
		if (facetEntityTypeIndex == null || facetEntityTypeIndex.isEmpty()) {
			return Collections.emptyList();
		} else {
			return facetEntityTypeIndex.getFacetReferencingEntityIdsFormula(formulaFactory, facetId);
		}
	}

	@Override
	public boolean isFacetInGroup(@Nonnull String referenceName, int groupId, int facetId) {
		return ofNullable(this.facetingEntities.get(referenceName))
			.map(it -> it.isFacetInGroup(groupId, facetId))
			.orElse(false);
	}

	@Override
	@Nonnull
	public Map<String, FacetReferenceIndex> getFacetingEntities() {
		return this.facetingEntities;
	}

	@Override
	public int getSize() {
		return this.facetingEntities.values()
			.stream()
			.mapToInt(FacetReferenceIndex::size)
			.sum();
	}

	/**
	 * Returns collection of {@link FacetIndexStoragePart} that were modified and need persistence to the persistent
	 * storage.
	 */
	public void getModifiedStorageParts(int entityIndexPK, @Nonnull TrappedChanges trappedChanges) {
		this.facetingEntities.entrySet()
			.stream()
			.filter(it -> this.dirtyIndexes.contains(it.getKey()))
			.map(
				it -> new FacetIndexStoragePart(
					entityIndexPK,
					it.getKey(),
					it.getValue().getNotGroupedFacetsAsMap().orElse(null),
					it.getValue().getGroupsAsMap()
				)
			)
			.forEach(trappedChanges::addChangeToStore);
	}

	/**
	 * Checks if the collection of faceting entities is empty.
	 *
	 * @return true if there are no faceting entities; false otherwise.
	 */
	public boolean isEmpty() {
		return this.facetingEntities.isEmpty();
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(512);
		this.facetingEntities.keySet().stream().sorted().forEach(it ->
			sb.append(it)
				.append(":\n")
				.append(this.facetingEntities.get(it).toString())
		);
		if (!sb.isEmpty()) {
			while (sb.charAt(sb.length() - 1) == '\n') {
				sb.deleteCharAt(sb.length() - 1);
			}
		}
		return sb.toString();
	}

	@Override
	public void resetDirty() {
		this.dirtyIndexes.clear();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public FacetIndexChanges createLayer() {
		return new FacetIndexChanges();
	}

	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public FacetIndex createCopyWithMergedTransactionalMemory(@Nullable FacetIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Set<Serializable> setOfDirtyIndexes = transactionalLayer.getStateCopyWithCommittedChanges(this.dirtyIndexes);
		if (setOfDirtyIndexes.isEmpty()) {
			return this;
		} else {
			final FacetIndex facetIndex = new FacetIndex(
				// this is a HACK - facetingEntities id indexes produce NonTransactionalCopy instead of type than generics would suggest
				(Map) transactionalLayer.getStateCopyWithCommittedChanges(this.facetingEntities)
			);
			ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
			return facetIndex;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirtyIndexes.removeLayer(transactionalLayer);
		this.facetingEntities.removeLayer(transactionalLayer);
		final FacetIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #facetingEntities} transactional map and its sub structure.
	 */
	public static class FacetIndexChanges {
		private final TransactionalContainerChanges<FacetEntityTypeIndexChanges, FacetReferenceIndex, FacetReferenceIndex> facetGroupIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull FacetReferenceIndex index) {
			this.facetGroupIndexChanges.addCreatedItem(index);
		}

		public void addRemovedItem(@Nonnull FacetReferenceIndex index) {
			this.facetGroupIndexChanges.addRemovedItem(index);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.facetGroupIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.facetGroupIndexChanges.cleanAll(transactionalLayer);
		}
	}

}
