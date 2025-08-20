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
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.facet.FacetGroupIndex.FacetGroupIndexChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * FacetGroupIndex contains information about all entity ids that use facet that is organized in this group as their
 * {@link Entity#getReference(String, int)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public class FacetGroupIndex implements TransactionalLayerProducer<FacetGroupIndexChanges, FacetGroupIndex>, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains primary key of the group. Might contain NULL if the group index encloses facets wouth group assignment.
	 */
	@Nullable private final Integer groupId;
	/**
	 * Index of {@link FacetIndex}, key is the {@link FacetIdIndex#getFacetId()}
	 */
	private final TransactionalMap<Integer, FacetIdIndex> facetIdIndexes;

	public FacetGroupIndex() {
		this.groupId = null;
		this.facetIdIndexes = new TransactionalMap<>(new HashMap<>(), FacetIdIndex.class, Function.identity());
	}

	public FacetGroupIndex(@Nullable Integer groupId) {
		this.groupId = groupId;
		this.facetIdIndexes = new TransactionalMap<>(new HashMap<>(), FacetIdIndex.class, Function.identity());
	}

	public FacetGroupIndex(@Nonnull Collection<FacetIdIndex> facetIdIndexes) {
		this(null, facetIdIndexes);
	}

	public FacetGroupIndex(@Nullable Integer groupId, @Nonnull Collection<FacetIdIndex> facetIdIndexes) {
		this.groupId = groupId;
		this.facetIdIndexes = new TransactionalMap<>(
			facetIdIndexes
				.stream()
				.collect(
					Collectors.toMap(
						FacetIdIndex::getFacetId,
						Function.identity()
					)
				),
			FacetIdIndex.class, Function.identity()
		);
	}

	FacetGroupIndex(@Nullable Integer groupId, @Nonnull Map<Integer, FacetIdIndex> facetIdIndexes) {
		this.groupId = groupId;
		this.facetIdIndexes = new TransactionalMap<>(facetIdIndexes, FacetIdIndex.class, Function.identity());
	}

	/**
	 * Adds new entity primary key to facet index of `facetPrimaryKey`.
	 *
	 * @return true if entity id was really added
	 */
	public boolean addFacet(int facetPrimaryKey, int entityPrimaryKey) {
		final FacetGroupIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		// fetch or create index for referenced entity id (inside correct type)
		final FacetIdIndex facetIdIndex = this.facetIdIndexes.computeIfAbsent(
			facetPrimaryKey,
			fPK -> {
				final FacetIdIndex fgIx = new FacetIdIndex(fPK);
				ofNullable(txLayer).ifPresent(it -> it.addCreatedItem(fgIx));
				return fgIx;
			});

		return facetIdIndex.addFacet(entityPrimaryKey);
	}

	/**
	 * Removes entity primary key from index of `facetPrimaryKey` facet.
	 *
	 * @return true if entity id was really removed
	 */
	public boolean removeFacet(int facetPrimaryKey, int entityPrimaryKey) {
		final FacetIdIndex facetIdIndex = this.facetIdIndexes.get(facetPrimaryKey);
		// fetch index for referenced entity type
		Assert.notNull(facetIdIndex, "Facet `" + facetPrimaryKey + "` not found in index (group: `" + this.groupId + "`)!");
		boolean removed = facetIdIndex.removeFacet(entityPrimaryKey);
		// if facet was removed check whether there are any data left
		if (removed && facetIdIndex.isEmpty()) {
			// we need to keep track of removed internal transactional memory related data structures
			final FacetGroupIndexChanges txLayer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			// remove the index entirely
			this.facetIdIndexes.remove(facetPrimaryKey);
			ofNullable(txLayer).ifPresent(it -> it.addRemovedItem(facetIdIndex));
		}
		return removed;
	}

	/**
	 * Returns true if there is no entity id linked to any facet in this group and the entire index is useless.
	 */
	public boolean isEmpty() {
		return this.facetIdIndexes
			.values()
			.stream()
			.allMatch(FacetIdIndex::isEmpty);
	}

	/**
	 * Returns count of all entity ids referring to all facets in this group.
	 */
	public int size() {
		return this.facetIdIndexes.values()
			.stream()
			.mapToInt(FacetIdIndex::size)
			.sum();
	}

	/**
	 * Returns array of all bitmaps that contain entity ids referring to facets of this group. If all bitmaps are
	 * combined by OR relation it would produce result of all entities referring this group.
	 */
	@Nonnull
	public Bitmap[] getFacetIdIndexesAsArray(Bitmap facetPrimaryKeys) {
		return StreamSupport.stream(facetPrimaryKeys.spliterator(), false)
			.map(this.facetIdIndexes::get)
			.map(it -> it == null ? EmptyBitmap.INSTANCE : it.getRecords())
			.toArray(Bitmap[]::new);
	}

	/**
	 * Returns facet index by its primary key.
	 */
	@Nullable
	public FacetIdIndex getFacetIdIndex(int facetPrimaryKey) {
		return this.facetIdIndexes.get(facetPrimaryKey);
	}

	/**
	 * Returns contents of this index as plain non-transactional map.
	 */
	@Nonnull
	public Map<Integer, Bitmap> getAsMap() {
		final Map<Integer, Bitmap> result = createHashMap(this.facetIdIndexes.size());
		for (Entry<Integer, FacetIdIndex> entry : this.facetIdIndexes.entrySet()) {
			result.put(entry.getKey(), entry.getValue().getRecords());
		}
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(
			ofNullable(this.groupId).map(it -> "GROUP " + it).orElse("[NO_GROUP]") + ":\n"
		);
		this.facetIdIndexes
			.keySet()
			.stream()
			.sorted()
			.forEach(facet -> sb.append("\t\t").append(this.facetIdIndexes.get(facet)).append("\n"));
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
	public FacetGroupIndexChanges createLayer() {
		return new FacetGroupIndexChanges();
	}

	@Nonnull
	@Override
	public FacetGroupIndex createCopyWithMergedTransactionalMemory(@Nullable FacetGroupIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final Map<Integer, FacetIdIndex> stateCopy = transactionalLayer.getStateCopyWithCommittedChanges(this.facetIdIndexes);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return new FacetGroupIndex(this.groupId, stateCopy);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.facetIdIndexes.removeLayer(transactionalLayer);
		final FacetGroupIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #facetIdIndexes} transactional map and its sub structure.
	 */
	public static class FacetGroupIndexChanges {
		private final TransactionalContainerChanges<Void, FacetIdIndex, FacetIdIndex> items = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull FacetIdIndex baseIndex) {
			this.items.addCreatedItem(baseIndex);
		}

		public void addRemovedItem(@Nonnull FacetIdIndex baseIndex) {
			this.items.addRemovedItem(baseIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.items.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.items.cleanAll(transactionalLayer);
		}

	}

}
